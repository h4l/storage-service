package weco.storage_service.bag_versioner.versioning.memory

import weco.fixtures.TestWith
import weco.storage_service.bag_versioner.versioning.{
  IngestVersionManagerDao,
  IngestVersionManagerDaoTestCases,
  VersionRecord
}

class MemoryIngestVersionManagerDaoTest
    extends IngestVersionManagerDaoTestCases[MemoryIngestVersionManagerDao] {
  override def withContext[R](
    testWith: TestWith[MemoryIngestVersionManagerDao, R]
  ): R =
    testWith(new MemoryIngestVersionManagerDao())

  override def withDao[R](initialRecords: Seq[VersionRecord])(
    testWith: TestWith[IngestVersionManagerDao, R]
  )(implicit dao: MemoryIngestVersionManagerDao): R = {
    dao.records = dao.records ++ initialRecords

    testWith(dao)
  }
}
