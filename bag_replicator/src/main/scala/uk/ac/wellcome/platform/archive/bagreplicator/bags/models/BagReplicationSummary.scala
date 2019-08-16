package uk.ac.wellcome.platform.archive.bagreplicator.bags.models

import java.time.Instant

import uk.ac.wellcome.platform.archive.common.operation.models.Summary

case class BagReplicationSummary[Request <: BagReplicationRequest](
  startTime: Instant,
  maybeEndTime: Option[Instant] = None,
  request: Request) extends Summary {

  override def toString: String =
    f"""|${request.request}
        |durationSeconds=$durationSeconds
        |duration=$formatDuration""".stripMargin
      .replaceAll("\n", ", ")

  def complete: BagReplicationSummary[Request] = this.copy(
    maybeEndTime = Some(Instant.now)
  )
}
