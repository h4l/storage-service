package uk.ac.wellcome.platform.archive.display.fixtures

import java.time.format.DateTimeFormatter

import weco.storage_service.bagit.models.BagInfo
import weco.storage_service.storage.models.{
  FileManifest,
  StorageLocation,
  StorageManifestFile
}

trait DisplayJsonHelpers {
  private def stringField[T](t: T): String =
    s"""
       |"${t.toString}"
   """.stripMargin

  def bagInfo(info: BagInfo): String =
    s"""
       |{
       |  "externalIdentifier": "${info.externalIdentifier}",
       |  ${optionalField(
         "externalDescription",
         stringField,
         info.externalDescription
       )}
       |  ${optionalField(
         "internalSenderIdentifier",
         stringField,
         info.internalSenderIdentifier
       )}
       |  ${optionalField(
         "internalSenderDescription",
         stringField,
         info.internalSenderDescription
       )}
       |  ${optionalField(
         "sourceOrganization",
         stringField,
         info.sourceOrganisation
       )}
       |  "payloadOxum": "${info.payloadOxum.toString}",
       |  "baggingDate": "${info.baggingDate.format(
         DateTimeFormatter.ISO_LOCAL_DATE
       )}",
       |  "type": "BagInfo"
       |}
     """.stripMargin

  def location(loc: StorageLocation): String =
    s"""
       |{
       |  "provider": {
       |    "id": "${loc.provider.id}",
       |    "type": "Provider"
       |  },
       |  "bucket": "${loc.prefix.namespace}",
       |  "path": "${loc.prefix.pathPrefix}",
       |  "type": "Location"
       |}
     """.stripMargin

  private def file(f: StorageManifestFile): String =
    s"""
       |{
       |  "type": "File",
       |  "path": "${f.path}",
       |  "name": "${f.name}",
       |  "checksum": "${f.checksum.value}",
       |  "size": ${f.size}
       |}
     """.stripMargin

  def manifest(fm: FileManifest): String =
    s"""
       |{
       |  "type": "BagManifest",
       |  "checksumAlgorithm": "${fm.checksumAlgorithm.value}",
       |  "files": [ ${asList(fm.files.sortBy { _.name }, file)} ]
       |}
     """.stripMargin

  def asList[T](elements: Seq[T], formatter: T => String): String =
    elements.map { formatter(_) }.mkString(",")

  def optionalField[T](
    fieldName: String,
    formatter: T => String,
    maybeObjectValue: Option[T],
    lastField: Boolean = false
  ): String =
    maybeObjectValue match {
      case None => ""
      case Some(o) =>
        s"""
           "$fieldName": ${formatter(o)}${if (!lastField) ","}
         """
    }
}
