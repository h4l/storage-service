package uk.ac.wellcome.platform.archive.bagreplicator.archive_to_access.services

import akka.Done
import uk.ac.wellcome.json.JsonUtil._
import uk.ac.wellcome.messaging.sns.{PublishAttempt, SNSWriter}
import uk.ac.wellcome.messaging.sqs.NotificationStream
import uk.ac.wellcome.platform.archive.bagreplicator.config.ReplicatorDestinationConfig
import uk.ac.wellcome.platform.archive.common.models.bagit.BagLocation
import uk.ac.wellcome.platform.archive.common.models.{BagRequest, ReplicationResult}
import uk.ac.wellcome.platform.archive.common.progress.models._
import uk.ac.wellcome.typesafe.Runnable

import scala.concurrent.{ExecutionContext, Future}

class BagReplicatorWorkerService(
  notificationStream: NotificationStream[BagRequest],
  bagStorageService: BagStorageService,
  replicatorDestinationConfig: ReplicatorDestinationConfig,
  progressSnsWriter: SNSWriter,
  outgoingSnsWriter: SNSWriter)(implicit ec: ExecutionContext)
    extends Runnable {

  def run(): Future[Done] =
    notificationStream.run(processMessage)

  def processMessage(bagRequest: BagRequest): Future[Unit] =
    for {
      result: Either[Throwable, BagLocation] <- bagStorageService.duplicateBag(
        sourceBagLocation = bagRequest.bagLocation,
        destinationConfig = replicatorDestinationConfig
      )
      _ <- sendProgressUpdate(
        bagRequest = bagRequest,
        result = result
      )
      _ <- sendOutgoingNotification(
        bagRequest = bagRequest,
        result = result
      )
    } yield ()

  def sendOutgoingNotification(
    bagRequest: BagRequest,
    result: Either[Throwable, BagLocation]): Future[Unit] =
    result match {
      case Left(_) => Future.successful(())
      case Right(dstBagLocation) =>
        val result = ReplicationResult(
          archiveRequestId = bagRequest.archiveRequestId,
          srcBagLocation = bagRequest.bagLocation,
          dstBagLocation = dstBagLocation
        )
        outgoingSnsWriter
          .writeMessage(
            result,
            subject = s"Sent by ${this.getClass.getSimpleName}"
          )
          .map { _ =>
            ()
          }
    }

  def sendProgressUpdate(
    bagRequest: BagRequest,
    result: Either[Throwable, BagLocation]): Future[PublishAttempt] = {
    val event: ProgressUpdate = result match {
      case Right(_) =>
        ProgressUpdate.event(
          id = bagRequest.archiveRequestId,
          description = "Bag replicated successfully"
        )
      case Left(_) =>
        ProgressStatusUpdate(
          id = bagRequest.archiveRequestId,
          status = Progress.Failed,
          affectedBag = None,
          events = List(ProgressEvent("Failed to replicate bag"))
        )
    }

    progressSnsWriter.writeMessage(
      event,
      subject = s"Sent by ${this.getClass.getSimpleName}"
    )
  }
}
