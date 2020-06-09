package uk.ac.wellcome.platform.archive.common.storage.services

import java.time.Instant

import grizzled.slf4j.Logging
import uk.ac.wellcome.platform.archive.common.bagit.models.{
  Bag,
  BagManifest,
  BagPath,
  BagVersion,
  MatchedLocation
}
import uk.ac.wellcome.platform.archive.common.bagit.services.BagMatcher
import uk.ac.wellcome.platform.archive.common.ingests.models.IngestID
import uk.ac.wellcome.platform.archive.common.storage.models._
import uk.ac.wellcome.storage.store.Readable
import uk.ac.wellcome.storage.streaming.InputStreamWithLength
import uk.ac.wellcome.storage.{ObjectLocation, ObjectLocationPrefix}

import scala.util.{Failure, Success, Try}

class StorageManifestException(message: String)
    extends RuntimeException(message)

class BadFetchLocationException(message: String)
    extends StorageManifestException(message)

class StorageManifestService(
  sizeFinder: SizeFinder
)(
  implicit streamReader: Readable[ObjectLocation, InputStreamWithLength]
) extends Logging {
  private val tagManifestFileFinder = new TagManifestFileFinder()

  def createManifest(
    ingestId: IngestID,
    bag: Bag,
    location: PrimaryStorageLocation,
    replicas: Seq[SecondaryStorageLocation],
    space: StorageSpace,
    version: BagVersion
  ): Try[StorageManifest] =
    for {
      bagRoot <- getBagRoot(location.prefix, version)

      replicaLocations <- getReplicaLocations(replicas, version)

      matchedLocations <- resolveFetchLocations(bag)

      entries <- createPathLocationMap(
        matchedLocations = matchedLocations,
        bagRoot = bagRoot,
        version = version
      )

      fileManifestFiles <- createManifestFiles(
        bagRoot = bagRoot,
        manifest = bag.manifest,
        entries = entries
      )

      tagManifestFiles <- createManifestFiles(
        bagRoot = bagRoot,
        manifest = bag.tagManifest,
        entries = entries
      )

      unreferencedTagManifestFiles <- getUnreferencedFiles(
        bagRoot = bagRoot,
        version = version,
        tagManifest = bag.tagManifest
      )

      storageManifest = StorageManifest(
        space = space,
        info = bag.info,
        version = version,
        manifest = FileManifest(
          checksumAlgorithm = bag.manifest.checksumAlgorithm,
          files = fileManifestFiles
        ),
        tagManifest = FileManifest(
          checksumAlgorithm = bag.tagManifest.checksumAlgorithm,
          files = tagManifestFiles ++ unreferencedTagManifestFiles
        ),
        location = PrimaryStorageLocation(
          provider = location.provider,
          prefix = bagRoot
        ),
        replicaLocations = replicaLocations,
        createdDate = Instant.now,
        ingestId = ingestId
      )
    } yield storageManifest

  /** The replicator writes bags inside a bucket to paths of the form
    *
    *     /{storageSpace}/{externalIdentifier}/v{version}
    *
    * All the versions of a bag should follow this convention, so if we
    * strip off the /:version prefix we'll find the root of all bags
    * with this (storage space, external ID) pair.
    *
    * TODO: It would be better if we passed a structured object out of
    * the replicator.
    *
    */
  private def getBagRoot(
    replicaRoot: ObjectLocationPrefix,
    version: BagVersion
  ): Try[ObjectLocationPrefix] =
    if (replicaRoot.path.endsWith(s"/$version")) {
      Success(
        replicaRoot.copy(
          path = replicaRoot.path.stripSuffix(s"/$version")
        )
      )
    } else {
      Failure(
        new StorageManifestException(
          // TODO: Move the toString method for ObjectLocationPrefix into scala-storage
          s"Malformed bag root: ${replicaRoot.namespace}/${replicaRoot.path} (expected suffix /$version)"
        )
      )
    }

  private def resolveFetchLocations(bag: Bag): Try[Seq[MatchedLocation]] =
    BagMatcher.correlateFetchEntries(bag) match {
      case Right(matchedLocations) => Success(matchedLocations)
      case Left(err) =>
        Failure(
          new StorageManifestException(
            s"Unable to resolve fetch entries: $err"
          )
        )
    }

  private def getSizeAndLocation(
    matchedLocation: MatchedLocation,
    bagRoot: ObjectLocationPrefix,
    version: BagVersion
  ): (ObjectLocation, Option[Long]) =
    matchedLocation.fetchMetadata match {
      // This is a concrete file inside the replicated bag,
      // so it's inside the versioned replica directory.
      case None =>
        (
          bagRoot.asLocation(version.toString, matchedLocation.bagPath.value),
          None
        )

      // This is referring to a fetch file somewhere else.
      // We need to check it's in another versioned directory
      // for this bag.
      case Some(fetchMetadata) =>
        val fetchLocation = ObjectLocation(
          namespace = fetchMetadata.uri.getHost,
          path = fetchMetadata.uri.getPath.stripPrefix("/")
        )

        if (fetchLocation.namespace != bagRoot.namespace) {
          throw new BadFetchLocationException(
            s"Fetch entry for ${matchedLocation.bagPath} refers to a file in the wrong namespace: ${fetchLocation.namespace}"
          )
        }

        // TODO: This check could actually look for a /v1, /v2, etc.
        if (!fetchLocation.path.startsWith(bagRoot.path + "/")) {
          throw new BadFetchLocationException(
            s"Fetch entry for ${matchedLocation.bagPath} refers to a file in the wrong path: /${fetchLocation.path}"
          )
        }

        (fetchLocation, fetchMetadata.length)
    }

  /** Every entry in the bag manifest will be either a:
    *
    *   - concrete file inside the replicated bag, or
    *   - a file referenced by the fetch file, which should be in a different
    *     versioned directory under the same bag root
    *
    * The map contains data
    *
    *   (bag path) -> (location, size if known)
    *
    */
  private def createPathLocationMap(
    matchedLocations: Seq[MatchedLocation],
    bagRoot: ObjectLocationPrefix,
    version: BagVersion
  ): Try[Map[BagPath, (ObjectLocation, Option[Long])]] =
    Try {
      matchedLocations.map { matchedLoc =>
        (
          matchedLoc.bagPath,
          getSizeAndLocation(matchedLoc, bagRoot, version)
        )
      }.toMap
    }

  private def createManifestFiles(
    manifest: BagManifest,
    entries: Map[BagPath, (ObjectLocation, Option[Long])],
    bagRoot: ObjectLocationPrefix
  ): Try[Seq[StorageManifestFile]] = Try {
    manifest.entries.map {
      case (bagPath, checksumValue) =>
        // This lookup should never file -- the BagMatcher populates the
        // entries from the original manifests in the bag.
        //
        // We wrap it in a Try block just in case, but this should never
        // throw in practice.
        val (location, maybeSize) = entries(bagPath)
        val path = location.path.stripPrefix(bagRoot.path + "/")

        val size = maybeSize match {
          case Some(s) => s
          case None =>
            sizeFinder.getSize(location) match {
              case Right(value)    => value
              case Left(readError) =>
                throw new StorageManifestException(
                  s"Error getting size of $location: ${readError.e}"
                )
            }
        }

        StorageManifestFile(
          checksum = checksumValue,
          name = bagPath.value,
          path = path,
          size = size
        )
    }.toSeq
  }

  // Get StorageManifestFile entries for everything not listed in either the manifest
  // or tag manifest BagIt files.
  //
  // This should only be the tagmanifest-*.txt files -- the verifier checks that these
  // are the only unreferenced files.
  //
  private def getUnreferencedFiles(
    bagRoot: ObjectLocationPrefix,
    version: BagVersion,
    tagManifest: BagManifest
  ): Try[Seq[StorageManifestFile]] =
    tagManifestFileFinder
      .getTagManifestFiles(
        prefix = bagRoot.asLocation(version.toString).asPrefix,
        algorithm = tagManifest.checksumAlgorithm
      )
      .map {
        // Remember to prefix all the entries with a version string
        _.map { f =>
          f.copy(path = s"$version/${f.path}")
        }
      }

  private def getReplicaLocations(
    replicas: Seq[SecondaryStorageLocation],
    version: BagVersion
  ): Try[Seq[SecondaryStorageLocation]] = {
    val rootReplicas =
      replicas
        .map { loc =>
          getBagRoot(replicaRoot = loc.prefix, version = version) match {
            case Success(prefix) =>
              Success(
                SecondaryStorageLocation(
                  provider = loc.provider,
                  prefix = prefix
                )
              )

            case Failure(err) => Failure(err)
          }
        }

    val successes = rootReplicas.collect { case Success(loc) => loc }
    val failures = rootReplicas.collect { case Failure(err)  => err }

    if (failures.isEmpty) {
      Success(successes)
    } else {
      Failure(
        new StorageManifestException(
          s"Malformed bag root in the replicas: $failures"
        )
      )
    }
  }
}
