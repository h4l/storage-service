package uk.ac.wellcome.platform.archive.bagunpacker.services

import java.io.EOFException

import uk.ac.wellcome.platform.archive.bagunpacker.storage.UnarchiverError
import uk.ac.wellcome.storage.StorageError

sealed trait UnpackerError {
  val e: Throwable
}

case class UnpackerStorageError(storageError: StorageError)
    extends UnpackerError {
  override val e: Throwable = storageError.e
}

case class UnpackerUnarchiverError(unarchiverError: UnarchiverError)
    extends UnpackerError {
  override val e: Throwable = unarchiverError.e
}

case class UnpackerEOFError(e: EOFException) extends UnpackerError

case class UnpackerUnexpectedError(e: Throwable) extends UnpackerError
