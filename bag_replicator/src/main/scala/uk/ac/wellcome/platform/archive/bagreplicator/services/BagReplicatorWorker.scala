package uk.ac.wellcome.platform.archive.bagreplicator.services

import java.time.Instant
import java.util.UUID
import akka.actor.ActorSystem
import cats.instances.try_._
import io.circe.Decoder
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import uk.ac.wellcome.messaging.sqsworker.alpakka.AlpakkaSQSWorkerConfig
import uk.ac.wellcome.monitoring.Metrics
import uk.ac.wellcome.platform.archive.bagreplicator.config.ReplicatorDestinationConfig
import uk.ac.wellcome.platform.archive.bagreplicator.replicator.Replicator
import uk.ac.wellcome.platform.archive.bagreplicator.replicator.models._
import uk.ac.wellcome.platform.archive.common.ingests.models.IngestID
import uk.ac.wellcome.platform.archive.common.ingests.services.IngestUpdater
import uk.ac.wellcome.platform.archive.common.operation.services._
import uk.ac.wellcome.platform.archive.common.storage.models._
import uk.ac.wellcome.platform.archive.common.{
  ReplicaCompletePayload,
  VersionedBagRootPayload
}
import uk.ac.wellcome.storage.{Location, Prefix}
import uk.ac.wellcome.storage.locking.{
  FailedLockingServiceOp,
  LockDao,
  LockingService
}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Try

class BagReplicatorWorker[
  IngestDestination,
  OutgoingDestination,
  SrcLocation,
  DstLocation <: Location,
  DstPrefix <: Prefix[DstLocation]
](
  val config: AlpakkaSQSWorkerConfig,
  ingestUpdater: IngestUpdater[IngestDestination],
  outgoingPublisher: OutgoingPublisher[OutgoingDestination],
  lockingService: LockingService[IngestStepResult[
    ReplicationSummary[DstPrefix]
  ], Try, LockDao[
    String,
    UUID
  ]],
  destinationConfig: ReplicatorDestinationConfig,
  replicator: Replicator[SrcLocation, DstLocation, DstPrefix],
  val metricsNamespace: String
)(
  implicit
  val mc: Metrics[Future],
  val as: ActorSystem,
  val sc: SqsAsyncClient,
  val wd: Decoder[VersionedBagRootPayload]
) extends IngestStepWorker[VersionedBagRootPayload, ReplicationSummary[
      DstPrefix
    ]] {
  override val visibilityTimeout: Duration = 3.minutes

  def processMessage(
    payload: VersionedBagRootPayload
  ): Try[IngestStepResult[ReplicationSummary[DstPrefix]]] =
    for {
      _ <- ingestUpdater.start(payload.ingestId)

      srcPrefix = payload.bagRoot

      dstPrefix = replicator.buildDestination(
        namespace = destinationConfig.namespace,
        space = payload.storageSpace,
        externalIdentifier = payload.externalIdentifier,
        version = payload.version
      )

      replicationRequest = ReplicationRequest(
        srcPrefix = srcPrefix,
        dstPrefix = dstPrefix
      )

      result <- lockingService
        .withLock(dstPrefix.toString) {
          replicate(payload.ingestId, replicationRequest)
        }
        .map(lockFailed(payload.ingestId, replicationRequest).apply(_))

      _ <- ingestUpdater.send(payload.ingestId, result)

      _ <- outgoingPublisher.sendIfSuccessful(
        result,
        ReplicaCompletePayload(
          context = payload.context,
          srcPrefix = replicationRequest.srcPrefix,
          dstLocation = replicationRequest
            .toReplicaLocation(
              replicaType = destinationConfig.replicaType
            ),
          version = payload.version
        )
      )
    } yield result

  def replicate(
    ingestId: IngestID,
    request: ReplicationRequest[DstPrefix]
  ): Try[IngestStepResult[ReplicationSummary[DstPrefix]]] = Try {
    replicator.replicate(
      ingestId = ingestId,
      request = request
    ) match {
      case ReplicationSucceeded(summary) => IngestStepSucceeded(summary)
      case ReplicationFailed(summary, e) => IngestFailed(summary, e)
    }
  }

  def lockFailed(
    ingestId: IngestID,
    request: ReplicationRequest[DstPrefix]
  ): PartialFunction[Either[FailedLockingServiceOp, IngestStepResult[
    ReplicationSummary[DstPrefix]
  ]], IngestStepResult[ReplicationSummary[DstPrefix]]] = {
    case Right(result) => result
    case Left(failedLockingServiceOp) =>
      warn(s"Unable to lock successfully: $failedLockingServiceOp")
      IngestShouldRetry(
        ReplicationSummary(
          ingestId = ingestId,
          startTime = Instant.now,
          request = request
        ),
        new Throwable(
          s"Unable to lock successfully: $failedLockingServiceOp"
        )
      )
  }
}
