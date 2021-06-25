package uk.ac.wellcome.platform.archive.bagreplicator.replicator.azure

import com.amazonaws.services.s3.model.S3ObjectSummary
import weco.fixtures.TestWith
import uk.ac.wellcome.platform.archive.bagreplicator.replicator.ReplicatorTestCases
import weco.storage.azure.{AzureBlobLocation, AzureBlobLocationPrefix}
import weco.storage.fixtures.AzureFixtures
import weco.storage.fixtures.AzureFixtures.Container
import uk.ac.wellcome.storage.listing.Listing
import uk.ac.wellcome.storage.listing.azure.AzureBlobLocationListing
import weco.storage.store.azure.AzureTypedStore
import weco.storage.streaming.Codec._
import uk.ac.wellcome.storage.transfer.azure.{
  AzurePrefixTransfer,
  AzurePutBlockTransfer
}

class AzureReplicatorTest
    extends ReplicatorTestCases[
      Container,
      S3ObjectSummary,
      AzureBlobLocation,
      AzureBlobLocationPrefix,
      AzurePrefixTransfer
    ]
    with AzureFixtures {

  override def withDstNamespace[R](testWith: TestWith[Container, R]): R =
    withAzureContainer { container =>
      testWith(container)
    }

  override def withPrefixTransfer[R](
    testWith: TestWith[AzurePrefixTransfer, R]
  ): R = {
    implicit val transfer: AzurePutBlockTransfer = new AzurePutBlockTransfer(
      blockSize = 1000
    )

    testWith(new AzurePrefixTransfer())
  }

  override def withReplicator[R](
    prefixTransferImpl: AzurePrefixTransfer
  )(testWith: TestWith[ReplicatorImpl, R]): R =
    testWith(new AzureReplicator(transfer = prefixTransferImpl.transfer) {
      override val prefixTransfer: AzurePrefixTransfer = prefixTransferImpl
    })

  override def createDstLocationWith(
    dstContainer: Container,
    name: String
  ): AzureBlobLocation =
    AzureBlobLocation(dstContainer.name, name = name)

  override def createDstPrefixWith(
    dstContainer: Container
  ): AzureBlobLocationPrefix =
    AzureBlobLocationPrefix(dstContainer.name, namePrefix = "")

  override val dstStringStore: AzureTypedStore[String] =
    AzureTypedStore[String]

  override val dstListing: Listing[AzureBlobLocationPrefix, AzureBlobLocation] =
    AzureBlobLocationListing()
}
