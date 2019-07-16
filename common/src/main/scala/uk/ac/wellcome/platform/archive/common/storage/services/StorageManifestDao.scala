package uk.ac.wellcome.platform.archive.common.storage.services

import uk.ac.wellcome.platform.archive.common.bagit.models.BagId
import uk.ac.wellcome.platform.archive.common.storage.models.StorageManifest
import uk.ac.wellcome.storage._
import uk.ac.wellcome.storage.store.{HybridStoreEntry, VersionedStore}

case class EmptyMetadata()

trait StorageManifestDao {
  val vhs: VersionedStore[BagId,
                          Int,
                          HybridStoreEntry[StorageManifest, EmptyMetadata]]

  def getLatest(id: BagId): Either[ReadError, StorageManifest] =
    vhs.getLatest(id).map { _.identifiedT.t }

  def get(id: BagId, version: Int): Either[ReadError, StorageManifest] =
    vhs.get(Version(id, version)).map { _.identifiedT.t }

  def put(
    storageManifest: StorageManifest): Either[WriteError, StorageManifest] =
    vhs
      .put(id = Version(storageManifest.id, storageManifest.version))(
        HybridStoreEntry(storageManifest, metadata = EmptyMetadata())
      )
      .map { _.identifiedT.t }

  def listVersions(bagId: BagId, before: Option[Int] = None): Either[ReadError, Seq[StorageManifest]]
}
