package uk.ac.wellcome.platform.archive.bagreplicator

import akka.actor.ActorSystem
import com.typesafe.config.Config
import uk.ac.wellcome.json.JsonUtil._
import uk.ac.wellcome.messaging.typesafe.{NotificationStreamBuilder, SNSBuilder}
import uk.ac.wellcome.platform.archive.bagreplicator.config.BagReplicatorConfig
import uk.ac.wellcome.platform.archive.bagreplicator.services.{BagReplicatorWorkerService, BagStorageService}
import uk.ac.wellcome.platform.archive.common.models.BagRequest
import uk.ac.wellcome.storage.s3.S3PrefixCopier
import uk.ac.wellcome.storage.typesafe.S3Builder
import uk.ac.wellcome.typesafe.WellcomeTypesafeApp
import uk.ac.wellcome.typesafe.config.builders.AkkaBuilder

import scala.concurrent.ExecutionContextExecutor

object Main extends WellcomeTypesafeApp {
  runWithConfig { config: Config =>
    implicit val actorSystem: ActorSystem = AkkaBuilder.buildActorSystem()

    implicit val executionContext: ExecutionContextExecutor =
      actorSystem.dispatcher

    val s3PrefixCopier = S3PrefixCopier(
      s3Client = S3Builder.buildS3Client(config)
    )

    val bagStorageService = new BagStorageService(
      s3PrefixCopier = s3PrefixCopier
    )

    new BagReplicatorWorkerService(
      notificationStream =
        NotificationStreamBuilder.buildStream[BagRequest](config),
      bagStorageService = bagStorageService,
      bagReplicatorConfig = BagReplicatorConfig.buildBagReplicatorConfig(config),
      progressSnsWriter =
        SNSBuilder.buildSNSWriter(config, namespace = "progress"),
      outgoingSnsWriter =
        SNSBuilder.buildSNSWriter(config, namespace = "outgoing")
    )
  }
}
