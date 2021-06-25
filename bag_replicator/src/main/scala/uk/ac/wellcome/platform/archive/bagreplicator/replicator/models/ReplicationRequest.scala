package uk.ac.wellcome.platform.archive.bagreplicator.replicator.models

import uk.ac.wellcome.platform.archive.bagreplicator.models.{
  PrimaryReplica,
  ReplicaType,
  SecondaryReplica
}
import weco.storage_service.storage.models.{
  PrimaryS3ReplicaLocation,
  ReplicaLocation,
  SecondaryAzureReplicaLocation,
  SecondaryS3ReplicaLocation
}
import uk.ac.wellcome.storage._
import weco.storage.azure.AzureBlobLocationPrefix
import weco.storage.s3.S3ObjectLocationPrefix

case class ReplicationRequest[DstPrefix <: Prefix[_ <: Location]](
  srcPrefix: S3ObjectLocationPrefix,
  dstPrefix: DstPrefix
) {
  def toReplicaLocation(
    replicaType: ReplicaType
  ): ReplicaLocation =
    (dstPrefix, replicaType) match {
      case (s3Prefix: S3ObjectLocationPrefix, PrimaryReplica) =>
        PrimaryS3ReplicaLocation(prefix = s3Prefix)

      case (s3Prefix: S3ObjectLocationPrefix, SecondaryReplica) =>
        SecondaryS3ReplicaLocation(prefix = s3Prefix)

      case (azurePrefix: AzureBlobLocationPrefix, SecondaryReplica) =>
        SecondaryAzureReplicaLocation(prefix = azurePrefix)

      case _ =>
        throw new IllegalArgumentException(
          s"Unsupported provider/replica type: prefix=$dstPrefix, replica type=$replicaType"
        )
    }
}
