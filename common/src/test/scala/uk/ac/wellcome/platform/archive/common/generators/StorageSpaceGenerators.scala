package uk.ac.wellcome.platform.archive.common.generators

import grizzled.slf4j.Logging
import uk.ac.wellcome.platform.archive.common.storage.models.StorageSpace

trait StorageSpaceGenerators extends StorageRandomGenerators with Logging {
  def createStorageSpace = {
    val space = randomAlphanumeric()
    debug(s"Creating StorageSpace: $space")
    StorageSpace(space)
  }
}
