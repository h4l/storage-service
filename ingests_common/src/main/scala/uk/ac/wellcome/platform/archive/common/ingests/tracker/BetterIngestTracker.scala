package uk.ac.wellcome.platform.archive.common.ingests.tracker

import uk.ac.wellcome.platform.archive.common.ingests.models._
import uk.ac.wellcome.storage.{Identified, StorageError, Version, VersionAlreadyExistsError}
import uk.ac.wellcome.storage.store.VersionedStore

sealed trait IngestTrackerError

case class IngestTrackerStoreError(err: StorageError) extends IngestTrackerError
case class IngestAlreadyExistsError(err: VersionAlreadyExistsError) extends IngestTrackerError

trait BetterIngestTracker {
  val underlying: VersionedStore[IngestID, Int, Ingest]

  def init(ingest: Ingest): Either[IngestTrackerError, Identified[Version[IngestID, Int], Ingest]]  =
    underlying.init(ingest.id)(ingest) match {
      case Right(value)                         => Right(value)
      case Left(err: VersionAlreadyExistsError) => Left(IngestAlreadyExistsError(err))
      case Left(err)                            => Left(IngestTrackerStoreError(err))
    }

  def get(id: IngestID): underlying.ReadEither =
    underlying.getLatest(id)

  def update(update: IngestUpdate): underlying.UpdateEither = {
    update match {
      case _: IngestEventUpdate =>
        underlying.update(update.id) { ingest =>
          ingest.copy(
            events = ingest.events ++ update.events
          )
        }

      case statusUpdate: IngestStatusUpdate =>
        underlying.update(update.id) { ingest =>
          // TODO: What if the update conflicts with the ingest?
          ingest.copy(
            bag = statusUpdate.affectedBag,
            status = statusUpdate.status,
            events = ingest.events ++ update.events
          )
        }

      case callbackStatusUpdate: IngestCallbackStatusUpdate =>
        underlying.update(update.id) { ingest =>
          // TODO: What if there's no status?
          ingest.copy(
            callback = ingest.callback.map { cb =>
              cb.copy(status = callbackStatusUpdate.callbackStatus)
            }
          )
        }
    }
  }
}
