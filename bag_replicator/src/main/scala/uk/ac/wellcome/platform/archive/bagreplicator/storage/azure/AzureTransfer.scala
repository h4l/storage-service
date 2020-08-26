package uk.ac.wellcome.platform.archive.bagreplicator.storage.azure

import java.io.{ByteArrayInputStream, InputStream}
import java.net.URL

import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.S3ObjectInputStream
import com.azure.storage.blob.BlobServiceClient
import com.azure.storage.blob.models.BlobRange
import com.azure.storage.blob.specialized.BlobInputStream
import grizzled.slf4j.Logging
import org.apache.commons.io.IOUtils
import uk.ac.wellcome.platform.archive.common.storage.models.ByteRange
import uk.ac.wellcome.platform.archive.common.storage.services.s3.{
  S3RangedReader,
  S3SizeFinder,
  S3Uploader
}
import uk.ac.wellcome.storage.azure.AzureBlobLocation
import uk.ac.wellcome.storage.s3.S3ObjectLocation
import uk.ac.wellcome.storage.transfer._

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

trait AzureTransfer[Context]
    extends Transfer[S3ObjectLocation, AzureBlobLocation]
    with Logging {
  implicit val s3Client: AmazonS3
  implicit val blobServiceClient: BlobServiceClient

  val blockSize: Long

  private val s3SizeFinder = new S3SizeFinder()

  private def runTransfer(
    src: S3ObjectLocation,
    dst: AzureBlobLocation,
    allowOverwrites: Boolean
  ): Either[TransferFailure[S3ObjectLocation, AzureBlobLocation], Unit] =
    for {
      s3Length <- s3SizeFinder.getSize(src) match {
        case Left(readError) =>
          Left(TransferSourceFailure(src, dst, readError.e))
        case Right(result) => Right(result)
      }

      context <- getContext(src, dst)

      result <- writeBlocks(
        src = src,
        dst = dst,
        s3Length = s3Length,
        allowOverwrites = allowOverwrites,
        context = context
      ) match {
        case Success(_)   => Right(())
        case Failure(err) => Left(TransferDestinationFailure(src, dst, err))
      }
    } yield result

  protected def getContext(
    src: S3ObjectLocation,
    dst: AzureBlobLocation
  ): Either[TransferSourceFailure[S3ObjectLocation, AzureBlobLocation], Context]

  protected def writeBlockToAzure(
    src: S3ObjectLocation,
    dst: AzureBlobLocation,
    range: BlobRange,
    blockId: String,
    s3Length: Long,
    context: Context
  ): Unit

  private def writeBlocks(
    src: S3ObjectLocation,
    dst: AzureBlobLocation,
    s3Length: Long,
    allowOverwrites: Boolean,
    context: Context
  ): Try[Unit] = {
    val blockClient = blobServiceClient
      .getBlobContainerClient(dst.container)
      .getBlobClient(dst.name)
      .getBlockBlobClient

    val ranges =
      BlobRangeUtil.getRanges(length = s3Length, blockSize = blockSize)
    val identifiers = BlobRangeUtil.getBlockIdentifiers(count = ranges.size)

    Try {
      identifiers.zip(ranges).foreach {
        case (blockId, range) =>
          debug(s"Uploading to $dst with range $range / block Id $blockId")
          writeBlockToAzure(
            src = src,
            dst = dst,
            range = range,
            blockId = blockId,
            s3Length = s3Length,
            context = context
          )
      }

      blockClient.commitBlockList(identifiers.toList.asJava, allowOverwrites)
    }
  }

  override protected def transferWithCheckForExisting(
    src: S3ObjectLocation,
    dst: AzureBlobLocation
  ): TransferEither =
    getAzureStream(dst) match {
      // If the destination object doesn't exist, we can go ahead and
      // start the transfer.
      case Failure(_) =>
        transferWithOverwrites(src, dst)

      case Success(dstStream) =>
        getS3Stream(src) match {
          // If both the source and the destination exist, we can skip
          // the copy operation.
          case Success(srcStream) =>
            val result = compare(
              src = src,
              dst = dst,
              srcStream = srcStream,
              dstStream = dstStream
            )

            // Remember to close the streams afterwards, or we might get
            // errors like
            //
            //    Unable to execute HTTP request: Timeout waiting for
            //    connection from pool
            //
            // See: https://github.com/wellcometrust/platform/issues/3600
            //      https://github.com/aws/aws-sdk-java/issues/269
            //
            srcStream.abort()
            srcStream.close()
            dstStream.close()

            result

          case Failure(err) =>
            // As above, we need to abort the input stream so we don't leave streams
            // open or get warnings from the SDK.
            dstStream.close()
            Left(TransferSourceFailure(src, dst, err))
        }
    }

  private def getS3Stream(
    location: S3ObjectLocation
  ): Try[S3ObjectInputStream] =
    Try {
      s3Client.getObject(location.bucket, location.key)
    }.map { _.getObjectContent }

  private def getAzureStream(
    location: AzureBlobLocation
  ): Try[BlobInputStream] =
    Try {
      blobServiceClient
        .getBlobContainerClient(location.container)
        .getBlobClient(location.name)
        .openInputStream()
    }

  private def compare(
    src: S3ObjectLocation,
    dst: AzureBlobLocation,
    srcStream: InputStream,
    dstStream: InputStream
  ): Either[
    TransferOverwriteFailure[S3ObjectLocation, AzureBlobLocation],
    TransferNoOp[S3ObjectLocation, AzureBlobLocation]
  ] =
    if (IOUtils.contentEquals(srcStream, dstStream)) {
      Right(TransferNoOp(src, dst))
    } else {
      Left(TransferOverwriteFailure(src, dst))
    }

  override protected def transferWithOverwrites(
    src: S3ObjectLocation,
    dst: AzureBlobLocation
  ): TransferEither =
    runTransfer(src, dst, allowOverwrites = true).map { _ =>
      TransferPerformed(src, dst)
    }
}

class AzurePutBlockTransfer(
  // The max length you can put in a single Put Block API call is 4000 MiB.
  // The class will load a block of this size into memory, so setting it too
  // high may cause issues.
  val blockSize: Long = 1000000000
)(
  implicit
  val s3Client: AmazonS3,
  val blobServiceClient: BlobServiceClient
) extends AzureTransfer[Unit] {

  private val rangedReader = new S3RangedReader()

  override protected def getContext(
    src: S3ObjectLocation,
    dst: AzureBlobLocation
  ): Either[TransferSourceFailure[S3ObjectLocation, AzureBlobLocation], Unit] =
    Right(())

  override protected def writeBlockToAzure(
    src: S3ObjectLocation,
    dst: AzureBlobLocation,
    range: BlobRange,
    blockId: String,
    s3Length: Long,
    context: Unit
  ): Unit = {
    // Note: this class is only used in the tests as a way to exercise the
    // logic in AzureTransfer[…], so the raw exception here is okay.
    val bytes = rangedReader.getBytes(
      location = src,
      range = ByteRange(range)
    ) match {
      case Right(value) => value
      case Left(err) =>
        throw new RuntimeException(
          s"Error reading chunk from S3 location $src (range $range): $err"
        )
    }

    val blockClient = blobServiceClient
      .getBlobContainerClient(dst.container)
      .getBlobClient(dst.name)
      .getBlockBlobClient

    blockClient.stageBlock(
      blockId,
      new ByteArrayInputStream(bytes),
      bytes.length
    )
  }
}

class AzurePutBlockFromUrlTransfer(
  implicit
  val s3Client: AmazonS3,
  val blobServiceClient: BlobServiceClient
) extends AzureTransfer[URL] {
  // The max length you can put in a single Put Block from URL API call is 100 MiB.
  // The class will load a block of this size into memory, so setting it too
  // high may cause issues.
  val blockSize: Long = 100000000L

  private val s3Uploader = new S3Uploader()

  override protected def getContext(
    src: S3ObjectLocation,
    dst: AzureBlobLocation
  ): Either[TransferSourceFailure[S3ObjectLocation, AzureBlobLocation], URL] =
    s3Uploader.getPresignedGetURL(src, expiryLength = 1.hour).left.map {
      readError =>
        TransferSourceFailure(src, dst, e = readError.e)
    }

  override protected def writeBlockToAzure(
    src: S3ObjectLocation,
    dst: AzureBlobLocation,
    range: BlobRange,
    blockId: String,
    s3Length: Long,
    presignedUrl: URL
  ): Unit = {
    val blockClient = blobServiceClient
      .getBlobContainerClient(dst.container)
      .getBlobClient(dst.name)
      .getBlockBlobClient

    blockClient.stageBlockFromUrl(blockId, presignedUrl.toString, range)
  }
}