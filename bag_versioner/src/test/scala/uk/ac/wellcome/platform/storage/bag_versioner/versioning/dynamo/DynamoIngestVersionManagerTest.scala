package uk.ac.wellcome.platform.storage.bag_versioner.versioning.dynamo

import org.scanamo.generic.auto._
import uk.ac.wellcome.fixtures.TestWith
import uk.ac.wellcome.platform.archive.common.bagit.models.ExternalIdentifier
import uk.ac.wellcome.platform.archive.common.ingests.models.IngestID
import uk.ac.wellcome.platform.archive.common.storage.models.StorageSpace
import uk.ac.wellcome.platform.storage.bag_versioner.versioning.{
  IngestVersionManager,
  IngestVersionManagerTestCases,
  VersionRecord
}
import uk.ac.wellcome.storage.{MaximaError, MaximaReadError}
import uk.ac.wellcome.storage.fixtures.DynamoFixtures.Table

import scala.language.higherKinds
import scala.util.{Failure, Try}

class DynamoIngestVersionManagerTest
    extends IngestVersionManagerTestCases[DynamoIngestVersionManagerDao, Table]
    with IngestVersionManagerTable {
  override def withDao[R](
    testWith: TestWith[DynamoIngestVersionManagerDao, R]
  )(implicit table: Table): R =
    testWith(
      new DynamoIngestVersionManagerDao(
        dynamoClient = dynamoClient,
        dynamoConfig = createDynamoConfigWith(table)
      )
    )

  override def withBrokenLookupExistingVersionDao[R](
    testWith: TestWith[DynamoIngestVersionManagerDao, R]
  )(implicit table: Table): R =
    testWith(
      new DynamoIngestVersionManagerDao(
        dynamoClient = dynamoClient,
        dynamoConfig = createDynamoConfigWith(table)
      ) {
        override def lookupExistingVersion(
          ingestId: IngestID
        ): Try[Option[VersionRecord]] =
          Failure(new Throwable("BOOM!"))
      }
    )

  override def withBrokenLookupLatestVersionForDao[R](
    testWith: TestWith[DynamoIngestVersionManagerDao, R]
  )(implicit table: Table): R =
    testWith(
      new DynamoIngestVersionManagerDao(
        dynamoClient = dynamoClient,
        dynamoConfig = createDynamoConfigWith(table)
      ) {
        override def lookupLatestVersionFor(
          externalIdentifier: ExternalIdentifier,
          storageSpace: StorageSpace
        ): Either[MaximaError, VersionRecord] =
          Left(MaximaReadError(new Throwable("BOOM!")))
      }
    )

  override def withBrokenStoreNewVersionDao[R](
    testWith: TestWith[DynamoIngestVersionManagerDao, R]
  )(implicit table: Table): R =
    testWith(
      new DynamoIngestVersionManagerDao(
        dynamoClient = dynamoClient,
        dynamoConfig = createDynamoConfigWith(table)
      ) {
        override def storeNewVersion(record: VersionRecord): Try[Unit] =
          Failure(new Throwable("BOOM!"))
      }
    )

  override def withManager[R](
    dao: DynamoIngestVersionManagerDao
  )(testWith: TestWith[IngestVersionManager, R])(implicit context: Table): R =
    testWith(
      new DynamoIngestVersionManager(dao)
    )
}
