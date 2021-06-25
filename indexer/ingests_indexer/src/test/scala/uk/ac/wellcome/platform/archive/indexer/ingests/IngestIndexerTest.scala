package uk.ac.wellcome.platform.archive.indexer.ingests

import java.time.Instant
import com.sksamuel.elastic4s.{ElasticClient, Index}
import io.circe.Json
import org.scalatest.Assertion
import weco.json.JsonUtil._
import weco.storage.generators.IngestGenerators
import weco.storage_service.ingests.models.{
  Ingest,
  IngestEvent
}
import uk.ac.wellcome.platform.archive.indexer.IndexerTestCases
import uk.ac.wellcome.platform.archive.indexer.ingests.fixtures.IngestsIndexerFixtures
import uk.ac.wellcome.platform.archive.indexer.ingests.models.IndexedIngest

import scala.concurrent.ExecutionContext.Implicits.global

class IngestIndexerTest
    extends IndexerTestCases[Ingest, IndexedIngest]
    with IngestGenerators
    with IngestsIndexerFixtures {

  override def createIndexer(
    client: ElasticClient,
    index: Index
  ): IngestIndexer =
    new IngestIndexer(client, index = index)

  override def createDocument: Ingest = createIngest

  override def id(ingest: Ingest): String = ingest.id.toString

  override def getDocument(index: Index, id: String): IndexedIngest =
    getT[IndexedIngest](index, id = id)

  override def assertMatch(
    indexedIngest: IndexedIngest,
    ingest: Ingest
  ): Assertion =
    IndexedIngest(ingest) shouldBe indexedIngest

  override def createDocumentPair: (Ingest, Ingest) = {
    val ingestId = createIngestID

    val olderIngest = createIngestWith(
      id = ingestId,
      events = Seq(
        IngestEvent(
          description = "event 1",
          createdDate = Instant.ofEpochMilli(101)
        )
      ),
      createdDate = Instant.ofEpochMilli(1)
    )

    val newerIngest = olderIngest.copy(
      events = Seq(
        IngestEvent(
          description = "event 1",
          createdDate = Instant.ofEpochMilli(101)
        ),
        IngestEvent(
          description = "event 2",
          createdDate = Instant.ofEpochMilli(102)
        )
      ),
      createdDate = Instant.ofEpochMilli(2)
    )

    assert(
      olderIngest.lastModifiedDate.get
        .isBefore(newerIngest.lastModifiedDate.get)
    )

    (olderIngest, newerIngest)
  }

  describe("orders updates when one ingest does not have a modified date") {
    val ingestId = createIngestID

    val olderIngest = createIngestWith(
      id = ingestId,
      events = Seq.empty,
      createdDate = Instant.ofEpochMilli(1)
    )

    val newerIngest = olderIngest.copy(
      events = Seq(
        IngestEvent(
          description = "event 1",
          createdDate = Instant.ofEpochMilli(101)
        ),
        IngestEvent(
          description = "event 2",
          createdDate = Instant.ofEpochMilli(102)
        )
      ),
      createdDate = Instant.ofEpochMilli(2)
    )

    assert(olderIngest.lastModifiedDate.isEmpty)
    assert(newerIngest.lastModifiedDate.isDefined)

    it("a newer ingest replaces an older ingest") {
      withLocalElasticsearchIndex(IngestsIndexConfig) { index =>
        val ingestsIndexer = new IngestIndexer(elasticClient, index = index)

        val future = ingestsIndexer
          .index(olderIngest)
          .flatMap { _ =>
            ingestsIndexer.index(newerIngest)
          }

        whenReady(future) { result =>
          result.value shouldBe newerIngest

          val storedIngest =
            getT[Json](index, id = ingestId.toString)
              .as[Map[String, Json]]
              .value

          storedIngest("events").asArray.get.size shouldBe 2
        }
      }
    }

    it("an older ingest does not replace a newer ingest") {
      withLocalElasticsearchIndex(IngestsIndexConfig) { index =>
        val ingestsIndexer = new IngestIndexer(elasticClient, index = index)

        val future = ingestsIndexer
          .index(newerIngest)
          .flatMap { _ =>
            ingestsIndexer.index(olderIngest)
          }

        whenReady(future) { result =>
          result.value shouldBe olderIngest

          val storedIngest =
            getT[Json](index, id = ingestId.toString)
              .as[Map[String, Json]]
              .value

          storedIngest("events").asArray.get.size shouldBe 2
        }
      }
    }
  }
}
