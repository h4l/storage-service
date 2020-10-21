package uk.ac.wellcome.platform.archive.indexer.files.models

object FileSuffix {
  def getSuffix(name: String): Option[String] = {
    val splitName = name.split(".+\\.")

    if (splitName.length > 1) {
      Some(splitName.last.toLowerCase)
    } else {
      None
    }
  }
}
