package uk.ac.wellcome.platform.archive.bagverifier.verify.steps

import uk.ac.wellcome.platform.archive.bagverifier.fixity.FileFixityCorrect
import uk.ac.wellcome.platform.archive.bagverifier.models.BagVerifierError
import weco.storage_service.bagit.models.Bag

trait VerifyPayloadOxum {
  def verifyPayloadOxumFileCount(bag: Bag): Either[BagVerifierError, Unit] = {
    val payloadOxumCount = bag.info.payloadOxum.numberOfPayloadFiles
    val manifestCount = bag.manifest.entries.size

    if (payloadOxumCount != manifestCount) {
      Left(
        BagVerifierError(
          s"Payload-Oxum has the wrong number of payload files: $payloadOxumCount, but bag manifest has $manifestCount"
        )
      )
    } else {
      Right(())
    }
  }

  def verifyPayloadOxumFileSize(
    bag: Bag,
    locations: Seq[FileFixityCorrect[_]]
  ): Either[BagVerifierError, Unit] = {
    // The Payload-Oxum octetstream sum only counts the size of files in the payload,
    // not manifest files such as the bag-info.txt file.
    // We need to filter those out.
    val dataFilePaths = bag.manifest.paths

    val actualSize =
      locations
        .filter { loc =>
          dataFilePaths.contains(loc.expectedFileFixity.path)
        }
        .map { _.size }
        .sum

    val expectedSize = bag.info.payloadOxum.payloadBytes

    if (actualSize == expectedSize) {
      Right(())
    } else {
      Left(
        BagVerifierError(
          s"Payload-Oxum has the wrong octetstream sum: $expectedSize bytes, but bag actually contains $actualSize bytes"
        )
      )
    }
  }
}
