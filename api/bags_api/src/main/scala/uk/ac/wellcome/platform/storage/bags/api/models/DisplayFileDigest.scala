package uk.ac.wellcome.platform.storage.bags.api.models

import io.circe.generic.extras.JsonKey
import uk.ac.wellcome.platform.archive.common.bagit.models.BagFile

case class DisplayFileDigest(
  checksum: String,
  path: String,
  @JsonKey("type") ontologyType: String = "File"
)

case object DisplayFileDigest {
  def apply(bagDigestFile: BagFile): DisplayFileDigest =
    DisplayFileDigest(
      checksum = bagDigestFile.checksum,
      path = bagDigestFile.path.toString
    )
}
