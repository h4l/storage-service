package uk.ac.wellcome.platform.archive.bag_tracker.storage.memory

import uk.ac.wellcome.platform.archive.bag_tracker.storage.StorageManifestDao
import weco.storage_service.bagit.models.{BagId, BagVersion}
import weco.storage_service.storage.models.StorageManifest

import uk.ac.wellcome.storage.{ReadError, Version}
import weco.storage.store.memory.{MemoryStore, MemoryVersionedStore}

class MemoryStorageManifestDao(
  val vhs: MemoryVersionedStore[BagId, StorageManifest]
) extends StorageManifestDao {
  override def listVersions(
    bagId: BagId,
    before: Option[BagVersion]
  ): Either[ReadError, Seq[StorageManifest]] = {
    val underlying =
      vhs.store
        .asInstanceOf[MemoryStore[
          Version[BagId, Int],
          StorageManifest
        ]]

    Right(
      underlying.entries
        .filter {
          case (_, manifest) => manifest.id == bagId
        }
        .map { case (_, manifest) => manifest }
        .toSeq
        .filter { manifest =>
          before match {
            case Some(beforeVersion) =>
              manifest.version.underlying < beforeVersion.underlying
            case _ => true
          }
        }
        .sortBy { _.version.underlying }
        .reverse
    )
  }
}
