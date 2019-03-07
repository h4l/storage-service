package uk.ac.wellcome.platform.archive.bag_register.services

import java.time.Instant

import uk.ac.wellcome.platform.archive.bag_register.models.RegistrationSummary
import uk.ac.wellcome.platform.archive.common.models.bagit.BagLocation
import uk.ac.wellcome.platform.archive.common.operation.{
  OperationCompleted,
  OperationFailure,
  OperationResult
}
import uk.ac.wellcome.platform.archive.common.services.StorageManifestService
import uk.ac.wellcome.platform.archive.common.storage.StorageManifestVHS

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class Register(
  storageManifestService: StorageManifestService,
  storageManifestVHS: StorageManifestVHS
) {

  type FutureSummary =
    Future[OperationResult[RegistrationSummary]]

  def update(
    location: BagLocation
  )(implicit
    ec: ExecutionContext): FutureSummary = {

    val registration = RegistrationSummary(
      startTime = Instant.now(),
      location = location
    )

    for {
      manifest <- storageManifestService
        .createManifest(location)

      registrationWithBagId = registration.copy(bagId = Some(manifest.id))

      completedRegistration <- storageManifestVHS
        .updateRecord(manifest)(_ => manifest)
        .transform {
          case Success(_) => completed(registrationWithBagId)
          case Failure(e) => failed(registrationWithBagId, e)
        }

    } yield completedRegistration
  }

  private def completed(r: RegistrationSummary) = {
    Success(OperationCompleted(r.complete))
  }

  private def failed(r: RegistrationSummary, e: Throwable) = {
    Success(OperationFailure(r.complete, e))
  }
}