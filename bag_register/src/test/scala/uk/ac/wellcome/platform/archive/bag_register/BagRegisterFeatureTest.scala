package uk.ac.wellcome.platform.archive.bag_register

import java.time.Instant

import org.scalatest.concurrent.{Eventually, IntegrationPatience}
import org.scalatest.{FunSpec, Matchers}
import uk.ac.wellcome.json.JsonUtil._
import uk.ac.wellcome.messaging.fixtures.SQS.QueuePair
import uk.ac.wellcome.messaging.memory.MemoryMessageSender
import uk.ac.wellcome.platform.archive.bag_register.fixtures.BagRegisterFixtures
import uk.ac.wellcome.platform.archive.common.bagit.models.BagId
import uk.ac.wellcome.platform.archive.common.generators.PayloadGenerators
import uk.ac.wellcome.platform.archive.common.ingests.models.InfrequentAccessStorageProvider
import uk.ac.wellcome.platform.archive.common.storage.models.{
  KnownReplicas,
  PrimaryStorageLocation
}
import uk.ac.wellcome.storage.ObjectLocation
import uk.ac.wellcome.storage.store.memory.MemoryStreamStore

class BagRegisterFeatureTest
    extends FunSpec
    with Matchers
    with BagRegisterFixtures
    with PayloadGenerators
    with Eventually
    with IntegrationPatience {

  it("sends an update if it registers a bag") {
    implicit val streamStore: MemoryStreamStore[ObjectLocation] =
    MemoryStreamStore[ObjectLocation]()

    val ingests = new MemoryMessageSender()

    val storageManifestDao = createStorageManifestDao()

    val createdAfterDate = Instant.now()
    val space = createStorageSpace
    val version = createBagVersion
    val dataFileCount = randomInt(1, 15)
    val externalIdentifier = createExternalIdentifier

    val bagId = BagId(
      space = space,
      externalIdentifier = externalIdentifier
    )

    implicit val namespace: String = randomAlphanumeric

    val (bagRoot, bagInfo) = createRegisterBagWith(
      externalIdentifier = externalIdentifier,
      space = space,
      version = version,
      dataFileCount = dataFileCount
    )

    val knownReplicas = KnownReplicas(
      location = PrimaryStorageLocation(
        provider = InfrequentAccessStorageProvider,
        prefix = bagRoot.asPrefix
      ),
      replicas = List.empty
    )

    val payload = createKnownReplicasPayloadWith(
      context = createPipelineContextWith(
        storageSpace = space
      ),
      version = version,
      knownReplicas = knownReplicas
    )

    withLocalSqsQueue { queue =>
      withBagRegisterWorker(
        queue = queue,
        ingests = ingests,
        storageManifestDao = storageManifestDao) { _ =>
        sendNotificationToSQS(queue, payload)

        eventually {
          val storageManifest =
            storageManifestDao.getLatest(bagId).right.value

          storageManifest.space shouldBe bagId.space
          storageManifest.info shouldBe bagInfo
          storageManifest.manifest.files should have size dataFileCount

          storageManifest.location shouldBe PrimaryStorageLocation(
            provider = InfrequentAccessStorageProvider,
            prefix = bagRoot
              .copy(
                path = bagRoot.path.stripSuffix(s"/$version")
              )
              .asPrefix
          )

          storageManifest.replicaLocations shouldBe empty

          storageManifest.createdDate.isAfter(createdAfterDate) shouldBe true

          assertBagRegisterSucceeded(
            ingestId = payload.ingestId,
            ingests = ingests
          )

          assertQueueEmpty(queue)
        }
      }
    }
  }

  it("handles a failed registration") {
    implicit val streamStore: MemoryStreamStore[ObjectLocation] =
      MemoryStreamStore[ObjectLocation]()

    val ingests = new MemoryMessageSender()

    // This registration will fail because when the register tries to read the
    // bag from the store, it won't find anything at the primary location
    // in this payload.
    val payload = createKnownReplicasPayload

    withLocalSqsQueueAndDlq { case QueuePair(queue, dlq) =>
      withBagRegisterWorker(queue = queue, ingests = ingests) { _ =>
        sendNotificationToSQS(queue, payload)

        eventually {
          assertBagRegisterFailed(
            ingestId = payload.ingestId,
            ingests = ingests
          )

          assertQueueEmpty(queue)
          assertQueueEmpty(dlq)
        }
      }
    }
  }
}
