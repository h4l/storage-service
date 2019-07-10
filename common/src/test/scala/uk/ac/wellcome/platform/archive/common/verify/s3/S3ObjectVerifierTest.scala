package uk.ac.wellcome.platform.archive.common.verify.s3

import uk.ac.wellcome.fixtures.TestWith
import uk.ac.wellcome.platform.archive.common.storage.LocationNotFound
import uk.ac.wellcome.platform.archive.common.verify._
import uk.ac.wellcome.storage.ObjectLocation
import uk.ac.wellcome.storage.fixtures.S3Fixtures.Bucket
import uk.ac.wellcome.storage.store.fixtures.BucketNamespaceFixtures

class S3ObjectVerifierTest
  extends VerifierTestCases[Bucket, Unit]
    with BucketNamespaceFixtures {
  override def withContext[R](testWith: TestWith[Unit, R]): R =
    testWith(())

  override def putString(location: ObjectLocation, contents: String)(implicit context: Unit): Unit =
    s3Client.putObject(
      location.namespace,
      location.path,
      contents
    )

  override def withVerifier[R](testWith: TestWith[Verifier[_], R])(implicit context: Unit): R =
    testWith(new S3ObjectVerifier())

  implicit val context: Unit = ()

  it("fails if the bucket doesn't exist") {
    val checksum = randomChecksum

    val location = createObjectLocation

    val verifiableLocation = createVerifiableLocationWith(
      location = location,
      checksum = checksum
    )

    val result =
      withVerifier {
        _.verify(verifiableLocation)
      }

    result shouldBe a[VerifiedFailure]

    val verifiedFailure = result.asInstanceOf[VerifiedFailure]

    verifiedFailure.location shouldBe verifiableLocation
    verifiedFailure.e shouldBe a[LocationNotFound[_]]
    verifiedFailure.e.getMessage should include(
      "Location not available!"
    )
  }

  it("fails if the key doesn't exist in the bucket") {
    withLocalS3Bucket { bucket =>
      val checksum = randomChecksum

      val location = createObjectLocationWith(bucket)

      val verifiableLocation = createVerifiableLocationWith(
        location = location,
        checksum = checksum
      )

      val result =
        withVerifier {
          _.verify(verifiableLocation)
        }

      result shouldBe a[VerifiedFailure]

      val verifiedFailure = result.asInstanceOf[VerifiedFailure]

      verifiedFailure.location shouldBe verifiableLocation
      verifiedFailure.e shouldBe a[LocationNotFound[_]]
      verifiedFailure.e.getMessage should include(
        "Location not available!"
      )
    }
  }
}
