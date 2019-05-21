package uk.ac.wellcome.platform.archive.bagverifier.services

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{FunSpec, Matchers, OptionValues, TryValues}
import uk.ac.wellcome.platform.archive.bagverifier.fixtures.BagVerifierFixtures
import uk.ac.wellcome.platform.archive.bagverifier.models.{
  VerificationFailureSummary,
  VerificationIncompleteSummary,
  VerificationSuccessSummary
}
import uk.ac.wellcome.platform.archive.common.fixtures.{
  BagLocationFixtures,
  FileEntry
}
import uk.ac.wellcome.platform.archive.common.storage.models.{
  IngestFailed,
  IngestStepSucceeded
}
import uk.ac.wellcome.storage.fixtures.S3

class BagVerifierTest
    extends FunSpec
    with Matchers
    with ScalaFutures
    with TryValues
    with OptionValues
    with BagLocationFixtures
    with BagVerifierFixtures
    with S3 {

  type StringTuple = List[(String, String)]

  val dataFileCount = 3

  val expectedFileCount: Int = dataFileCount + List(
    "manifest-sha256.txt",
    "bagit.txt",
    "bag-info.txt").size

  it("passes a bag with correct checksum values ") {
    withLocalS3Bucket { bucket =>
      withBag(
        storageBackend,
        namespace = bucket.name,
        dataFileCount = dataFileCount) {
        case (root, _) =>
          withVerifier { verifier =>
            val ingestStep = verifier.verify(root)
            val result = ingestStep.success.get

            result shouldBe a[IngestStepSucceeded[_]]
            result.summary shouldBe a[VerificationSuccessSummary]

            val summary = result.summary
              .asInstanceOf[VerificationSuccessSummary]
            val verification = summary.verification.value

            verification.locations should have size expectedFileCount
          }
      }
    }
  }

  it("fails a bag with an incorrect checksum in the file manifest") {
    withLocalS3Bucket { bucket =>
      withBag(
        storageBackend,
        namespace = bucket.name,
        dataFileCount = dataFileCount,
        createDataManifest = dataManifestWithWrongChecksum) {
        case (root, _) =>
          withVerifier { verifier =>
            val ingestStep = verifier.verify(root)
            val result = ingestStep.success.get

            result shouldBe a[IngestFailed[_]]
            result.summary shouldBe a[VerificationFailureSummary]

            val summary = result.summary
              .asInstanceOf[VerificationFailureSummary]
            val verification = summary.verification.value

            verification.success should have size expectedFileCount - 1
            verification.failure should have size 1

            val location = verification.failure.head
            val error = location.e

            error shouldBe a[RuntimeException]
            error.getMessage should startWith("Checksum values do not match:")
          }
      }
    }
  }

  it("fails a bag with an incorrect checksum in the tag manifest") {
    withLocalS3Bucket { bucket =>
      withBag(
        storageBackend,
        namespace = bucket.name,
        dataFileCount = dataFileCount,
        createTagManifest = tagManifestWithWrongChecksum) {
        case (root, _) =>
          withVerifier { verifier =>
            val ingestStep = verifier.verify(root)
            val result = ingestStep.success.get

            result shouldBe a[IngestFailed[_]]
            result.summary shouldBe a[VerificationFailureSummary]

            val summary = result.summary
              .asInstanceOf[VerificationFailureSummary]
            val verification = summary.verification.value

            verification.success should have size expectedFileCount - 1
            verification.failure should have size 1

            val location = verification.failure.head
            val error = location.e

            error shouldBe a[RuntimeException]
            error.getMessage should startWith("Checksum values do not match:")
          }
      }
    }
  }

  it("fails a bag if the file manifest refers to a non-existent file") {
    def createDataManifestWithExtraFile(
      dataFiles: StringTuple): Option[FileEntry] =
      createValidDataManifest(
        dataFiles ++ List(("doesnotexist", "doesnotexist"))
      )

    withLocalS3Bucket { bucket =>
      withBag(
        storageBackend,
        namespace = bucket.name,
        dataFileCount = dataFileCount,
        createDataManifest = createDataManifestWithExtraFile) {
        case (root, _) =>
          withVerifier { verifier =>
            val ingestStep = verifier.verify(root)
            val result = ingestStep.success.get

            result shouldBe a[IngestFailed[_]]
            result.summary shouldBe a[VerificationFailureSummary]

            val summary = result.summary
              .asInstanceOf[VerificationFailureSummary]
            val verification = summary.verification.value

            verification.success should have size expectedFileCount
            verification.failure should have size 1

            val location = verification.failure.head
            val error = location.e

            error shouldBe a[RuntimeException]
            error.getMessage should startWith(
              "The specified key does not exist")
          }
      }
    }
  }

  it("fails a bag if the file manifest does not exist") {
    def noDataManifest(files: StringTuple): Option[FileEntry] = None

    withLocalS3Bucket { bucket =>
      withBag(
        storageBackend,
        namespace = bucket.name,
        createDataManifest = noDataManifest) {
        case (root, _) =>
          withVerifier { verifier =>
            val ingestStep = verifier.verify(root)
            val result = ingestStep.success.get

            result shouldBe a[IngestFailed[_]]
            result.summary shouldBe a[VerificationIncompleteSummary]

            val summary = result.summary
              .asInstanceOf[VerificationIncompleteSummary]
            val error = summary.e

            error shouldBe a[RuntimeException]
            error.getMessage should startWith("Error getting file manifest")
          }
      }
    }
  }

  it("fails a bag if the tag manifest does not exist") {
    def noTagManifest(files: StringTuple): Option[FileEntry] = None

    withLocalS3Bucket { bucket =>
      withBag(
        storageBackend,
        namespace = bucket.name,
        createTagManifest = noTagManifest) {
        case (root, _) =>
          withVerifier { verifier =>
            val ingestStep = verifier.verify(root)
            val result = ingestStep.success.get

            result shouldBe a[IngestFailed[_]]
            result.summary shouldBe a[VerificationIncompleteSummary]

            val summary = result.summary
              .asInstanceOf[VerificationIncompleteSummary]
            val error = summary.e

            error shouldBe a[RuntimeException]
            error.getMessage should startWith("Error getting tag manifest")
          }
      }
    }
  }
}