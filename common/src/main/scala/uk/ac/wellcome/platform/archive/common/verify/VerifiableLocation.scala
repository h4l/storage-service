package uk.ac.wellcome.platform.archive.common.verify

import java.io.InputStream

import com.gu.scanamo.DynamoFormat
import com.gu.scanamo.error.TypeCoercionError
import grizzled.slf4j.Logging
import io.circe.{Decoder, Encoder, Json}
import org.apache.commons.codec.binary.Hex
import org.apache.commons.codec.digest.DigestUtils.{getDigest, updateDigest}
import org.apache.commons.codec.digest.MessageDigestAlgorithms
import uk.ac.wellcome.json.JsonUtil.{fromJson, toJson}
import uk.ac.wellcome.storage.ObjectLocation

import scala.util.Try

case class VerifiableLocation(
  objectLocation: ObjectLocation,
  checksum: Checksum
)

case class Checksum(
  algorithm: HashingAlgorithm,
  value: ChecksumValue
)
object Checksum extends Logging {
  def create(
    inputStream: InputStream,
    algorithm: HashingAlgorithm
  ): Try[Checksum] = {
    debug(s"Creating Checksum for $inputStream with  $algorithm")
    val checksumValue = ChecksumValue.create(inputStream, algorithm)
    val checksum = checksumValue.map(Checksum(algorithm, _))
    debug(s"Got: $checksum")
    checksum
  }
}

sealed trait HashingAlgorithm {
  val value: String
  val pathRepr: String
  override def toString: String = value
}

case object SHA256 extends HashingAlgorithm {
  val value = MessageDigestAlgorithms.SHA_256
  val pathRepr = "sha256"
}

case object MD5 extends HashingAlgorithm {
  val value = MessageDigestAlgorithms.MD5
  val pathRepr = "md5"
}

case class ChecksumValue(value: String) {
  override def toString: String = value
}
object ChecksumValue extends Logging {
  def create(raw: String) = {
    ChecksumValue(raw.trim)
  }

  def create(
    inputStream: InputStream,
    algorithm: HashingAlgorithm
  ): Try[ChecksumValue] = {
    debug(s"Creating ChecksumValue from $inputStream, $algorithm")

    val checksumValue = Try {
      ChecksumValue(
        Hex.encodeHexString(
          updateDigest(
            getDigest(algorithm.value),
            inputStream
          ).digest
        )
      )
    }

    debug(s"Got: $checksumValue")

    checksumValue
  }

  implicit val enc =
    Encoder.instance[ChecksumValue](o => Json.fromString(o.toString))

  implicit val dec = Decoder.instance[ChecksumValue](cursor =>
    cursor.value.as[String].map(ChecksumValue(_)))

  implicit def fmt =
    DynamoFormat.xmap[ChecksumValue, String](
      fromJson[ChecksumValue](_)(dec).toEither.left
        .map(TypeCoercionError)
    )(
      toJson[ChecksumValue](_).get
    )
}

sealed trait FailedChecksum
case class FailedChecksumCreation(algorithm: HashingAlgorithm, e: Throwable) extends Throwable(s"Could not vreate checksum: ${e.getMessage}") with FailedChecksum
case class FailedChecksumNoMatch(a: Checksum, b: Checksum) extends Throwable("Checksum values do not match!") with FailedChecksum
case class FailedChecksumLocationNotFound(location: VerifiableLocation) extends Throwable("VerifiableLocation not found!") with FailedChecksum