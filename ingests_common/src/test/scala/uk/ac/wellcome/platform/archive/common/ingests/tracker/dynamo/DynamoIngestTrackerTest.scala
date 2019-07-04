package uk.ac.wellcome.platform.archive.common.ingests.tracker.dynamo

import com.amazonaws.services.dynamodbv2.model._
import org.scanamo.auto._
import org.scanamo.time.JavaTimeFormats._
import uk.ac.wellcome.fixtures.TestWith
import uk.ac.wellcome.platform.archive.common.ingests.models.{Ingest, IngestID}
import uk.ac.wellcome.platform.archive.common.ingests.models.IngestID._
import uk.ac.wellcome.platform.archive.common.ingests.tracker.{
  IngestTracker,
  IngestTrackerTestCases
}
import uk.ac.wellcome.storage.{
  ReadError,
  StoreReadError,
  StoreWriteError,
  Version
}
import uk.ac.wellcome.storage.dynamo._
import uk.ac.wellcome.storage.fixtures.DynamoFixtures
import uk.ac.wellcome.storage.fixtures.DynamoFixtures.{Table => DynamoTable}
import uk.ac.wellcome.storage.generators.RandomThings
import uk.ac.wellcome.storage.store.VersionedStore
import uk.ac.wellcome.storage.store.dynamo.DynamoHashStore

class DynamoIngestTrackerTest
    extends IngestTrackerTestCases[(DynamoTable, DynamoTable)]
    with DynamoFixtures
    with RandomThings {
  override def withContext[R](
    testWith: TestWith[(DynamoTable, DynamoTable), R]): R =
    withSpecifiedTable(createIngestTrackerTable) { ingestTrackerTable =>
      withSpecifiedTable(createBagIdLookupTable) { bagIdLookupTable =>
        testWith((ingestTrackerTable, bagIdLookupTable))
      }
    }

  override def withIngestTracker[R](initialIngests: Seq[Ingest])(
    testWith: TestWith[IngestTracker, R])(
    implicit tables: (DynamoTable, DynamoTable)): R = {
    val (ingestTrackerTable, bagIdLookupTable) = tables

    val tracker = new DynamoIngestTracker(
      config = createDynamoConfigWith(ingestTrackerTable),
      bagIdLookupConfig = createDynamoConfigWith(bagIdLookupTable)
    )

    initialIngests.foreach { ingest =>
      tracker.init(ingest)
    }

    testWith(tracker)
  }

  override def createTable(table: DynamoTable): DynamoTable =
    createIngestTrackerTable(table)

  def createIngestTrackerTable(table: DynamoTable): DynamoTable =
    createTableWithHashKey(
      table,
      keyName = "id",
      keyType = ScalarAttributeType.S
    )

  def createBagIdLookupTable(table: DynamoTable): DynamoTable =
    createTableWithHashRangeKey(
      table,
      hashKeyName = "bagId",
      hashKeyType = ScalarAttributeType.S,
      rangeKeyName = "ingestDate",
      rangeKeyType = ScalarAttributeType.N
    )

  private def withBrokenPutTracker[R](testWith: TestWith[IngestTracker, R])(
    implicit tables: (DynamoTable, DynamoTable)): R = {
    val (ingestTrackerTable, bagIdLookupTable) = tables

    val config = createDynamoConfigWith(ingestTrackerTable)

    testWith(
      new DynamoIngestTracker(
        config = createDynamoConfigWith(ingestTrackerTable),
        bagIdLookupConfig = createDynamoConfigWith(bagIdLookupTable)
      ) {
        override val underlying = new VersionedStore[IngestID, Int, Ingest](
          new DynamoHashStore[IngestID, Int, Ingest](config) {
            override def put(id: Version[IngestID, Int])(
              t: Ingest): WriteEither =
              Left(StoreWriteError(new Throwable("BOOM!")))
          }
        )
      }
    )
  }

  override def withBrokenUnderlyingInitTracker[R](
    testWith: TestWith[IngestTracker, R])(
    implicit tables: (DynamoTable, DynamoTable)): R =
    withBrokenPutTracker { tracker =>
      testWith(tracker)
    }

  override def withBrokenUnderlyingGetTracker[R](
    testWith: TestWith[IngestTracker, R])(
    implicit tables: (DynamoTable, DynamoTable)): R = {
    val (ingestTrackerTable, bagIdLookupTable) = tables

    val config = createDynamoConfigWith(ingestTrackerTable)

    testWith(
      new DynamoIngestTracker(
        config = config,
        bagIdLookupConfig = createDynamoConfigWith(bagIdLookupTable)
      ) {
        override val underlying = new VersionedStore[IngestID, Int, Ingest](
          new DynamoHashStore[IngestID, Int, Ingest](config) {
            override def max(hashKey: IngestID): Either[ReadError, Int] =
              Left(StoreReadError(new Throwable("BOOM!")))
          }
        )
      }
    )
  }

  override def withBrokenUnderlyingUpdateTracker[R](
    testWith: TestWith[IngestTracker, R])(
    implicit tables: (DynamoTable, DynamoTable)): R =
    withBrokenPutTracker { tracker =>
      testWith(tracker)
    }

  // TODO: Add tests for handling DynamoDB errors

  // TODO: Add tests that lookupBagId returns a finite list of results

  // TODO: Add tests that failing to store the bag ID lookup don't fail the overall result
}