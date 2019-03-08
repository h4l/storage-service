package uk.ac.wellcome.platform.archive.bagunpacker.services

import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.{FunSpec, Matchers}
import uk.ac.wellcome.json.JsonUtil._
import uk.ac.wellcome.platform.archive.bagunpacker.fixtures.{
  CompressFixture,
  TestArchive,
  WorkerServiceFixture
}
import uk.ac.wellcome.platform.archive.common.fixtures.RandomThings
import uk.ac.wellcome.platform.archive.common.models.{
  BagRequest,
  StorageSpace,
  UnpackBagRequest
}
import uk.ac.wellcome.platform.archive.common.models.bagit.{
  BagLocation,
  BagPath
}
import uk.ac.wellcome.platform.archive.common.progress.ProgressUpdateAssertions
import uk.ac.wellcome.platform.archive.common.progress.models.Progress

class UnpackerWorkerTest
    extends FunSpec
    with Matchers
    with ScalaFutures
    with RandomThings
    with WorkerServiceFixture
    with IntegrationPatience
    with CompressFixture
    with ProgressUpdateAssertions {

  it("receives and processes a notification") {
    withApp {
      case (_, srcBucket, queue, progressTopic, outgoingTopic) =>
        val (archiveFile, filesInArchive, entries) =
          createTgzArchiveWithRandomFiles()
        withArchive(srcBucket, archiveFile) { archiveLocation =>
          val requestId = randomUUID

          withBagNotification(
            queue,
            srcBucket,
            requestId,
            TestArchive(archiveFile, filesInArchive, entries, archiveLocation)
          ) { unpackBagRequest =>
            eventually {

              val expectedNotification = BagRequest(
                requestId = unpackBagRequest.requestId,
                bagLocation = BagLocation(
                  storageNamespace = srcBucket.name,
                  storagePrefix = None,
                  storageSpace = unpackBagRequest.storageSpace,
                  bagPath = BagPath(unpackBagRequest.requestId.toString)
                )
              )

              assertSnsReceivesOnly[BagRequest](
                expectedNotification,
                outgoingTopic
              )

              assertTopicReceivesProgressEventUpdate(
                requestId = unpackBagRequest.requestId,
                progressTopic = progressTopic
              ) { events =>
                events.map {
                  _.description
                } shouldBe List(
                  "Unpacker succeeded"
                )
              }
            }
          }
        }
    }
  }

  it("sends a failed Progress update if it cannot read the bag") {
    withApp {
      case (service, _, _, progressTopic, outgoingTopic) =>
        val unpackBagRequest = UnpackBagRequest(
          requestId = randomUUID,
          sourceLocation = createObjectLocation,
          storageSpace = StorageSpace(randomAlphanumeric())
        )

        val future = service.processMessage(unpackBagRequest)

        whenReady(future) { _ =>
          assertSnsReceivesNothing(outgoingTopic)

          assertTopicReceivesProgressStatusUpdate(
            requestId = unpackBagRequest.requestId,
            progressTopic = progressTopic,
            status = Progress.Failed
          ) { events =>
            events.map { _.description } shouldBe List("Unpacker failed")
          }
        }
    }
  }
}