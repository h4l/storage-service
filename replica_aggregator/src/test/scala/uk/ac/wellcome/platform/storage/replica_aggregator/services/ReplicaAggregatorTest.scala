package uk.ac.wellcome.platform.storage.replica_aggregator.services

import java.time.Instant

import org.scalatest.{EitherValues, FunSpec, Matchers, TryValues}
import uk.ac.wellcome.fixtures.TestWith
import uk.ac.wellcome.platform.archive.common.fixtures.StorageRandomThings
import uk.ac.wellcome.platform.archive.common.ingests.models.InfrequentAccessStorageProvider
import uk.ac.wellcome.platform.archive.common.storage.models.{PrimaryStorageLocation, StorageLocation}
import uk.ac.wellcome.platform.storage.replica_aggregator.generators.StorageLocationGenerators
import uk.ac.wellcome.platform.storage.replica_aggregator.models._
import uk.ac.wellcome.storage.maxima.memory.MemoryMaxima
import uk.ac.wellcome.storage.store.memory.{MemoryStore, MemoryVersionedStore}
import uk.ac.wellcome.storage.{UpdateWriteError, Version}

import scala.util.{Success, Try}

class ReplicaAggregatorTest
    extends FunSpec
    with Matchers
    with EitherValues
    with TryValues
    with StorageLocationGenerators
    with StorageRandomThings {

  def createReplicaResultWith(
    storageLocation: StorageLocation = PrimaryStorageLocation(
      provider = InfrequentAccessStorageProvider,
      prefix = createObjectLocationPrefix
    )
  ): ReplicaResult =
    ReplicaResult(
      storageLocation = storageLocation,
      timestamp = Instant.now
    )

  def createReplicaResult: ReplicaResult =
    createReplicaResultWith()

  def withAggregator[R](
    versionedStore: MemoryVersionedStore[
      ReplicaPath,
      AggregatorInternalRecord
    ] = MemoryVersionedStore[ReplicaPath, AggregatorInternalRecord](
      initialEntries = Map.empty
    )
  )(testWith: TestWith[ReplicaAggregator, R]): R =
    testWith(
      new ReplicaAggregator(versionedStore)
    )

  describe("handling a primary replica") {
    val primaryLocation = createPrimaryLocation

    val replicaResult = createReplicaResultWith(
      storageLocation = primaryLocation
    )

    val versionedStore =
      MemoryVersionedStore[ReplicaPath, AggregatorInternalRecord](
        initialEntries = Map.empty
      )

    val result =
      withAggregator(versionedStore) {
        _.aggregate(replicaResult)
      }

    val expectedRecord =
      AggregatorInternalRecord(
        location = Some(primaryLocation),
        replicas = List.empty
      )

    it("returns the correct record") {
      result.success.value shouldBe expectedRecord
    }

    it("stores the replica in the underlying store") {
      val path = ReplicaPath(replicaResult.storageLocation.prefix.path)

      versionedStore
        .getLatest(path)
        .right
        .value
        .identifiedT shouldBe expectedRecord
    }
  }

  describe("handling a secondary replica") {
    val secondaryLocation = createSecondaryLocation

    val replicaResult = createReplicaResultWith(
      storageLocation = secondaryLocation
    )

    val versionedStore =
      MemoryVersionedStore[ReplicaPath, AggregatorInternalRecord](
        initialEntries = Map.empty
      )

    val result =
      withAggregator(versionedStore) {
        _.aggregate(replicaResult)
      }

    val expectedRecord =
      AggregatorInternalRecord(
        location = None,
        replicas = List(secondaryLocation)
      )

    it("returns the correct record") {
      result.success.value shouldBe expectedRecord
    }

    it("stores the replica in the underlying store") {
      val path = ReplicaPath(replicaResult.storageLocation.prefix.path)

      versionedStore
        .getLatest(path)
        .right
        .value
        .identifiedT shouldBe expectedRecord
    }
  }

  describe("handling multiple updates to the same replica") {
    val prefix = createObjectLocationPrefix

    val location1 = createSecondaryLocationWith(
      prefix = prefix.copy(namespace = randomAlphanumeric)
    )
    val location2 = createPrimaryLocationWith(
      prefix = prefix.copy(namespace = randomAlphanumeric)
    )
    val location3 = createSecondaryLocationWith(
      prefix = prefix.copy(namespace = randomAlphanumeric)
    )

    val locations = Seq(location1, location2, location3)

    val versionedStore =
      MemoryVersionedStore[ReplicaPath, AggregatorInternalRecord](
        initialEntries = Map.empty
      )

    val results: Seq[AggregatorInternalRecord] =
      withAggregator(versionedStore) { aggregator =>
        locations
          .map { storageLocation => createReplicaResultWith(storageLocation = storageLocation) }
          .map { replicaResult => aggregator.aggregate(replicaResult) }
          .map { _.success.value }
      }

    it("returns the correct records") {
      results shouldBe Seq(
        AggregatorInternalRecord(
          location = None,
          replicas = List(location1)
        ),
        AggregatorInternalRecord(
          location = Some(location2),
          replicas = List(location1)
        ),
        AggregatorInternalRecord(
          location = Some(location2),
          replicas = List(location1, location3)
        ),
      )
    }

    it("stores the replica in the underlying store") {
      val path = ReplicaPath(prefix.path)

      val expectedRecord =
        AggregatorInternalRecord(
          location = Some(location2),
          replicas = List(location1, location3)
        )

      versionedStore
        .getLatest(path)
        .right
        .value
        .identifiedT shouldBe expectedRecord
    }
  }

  it("stores different replicas under different paths") {
    val primaryLocation1 = createPrimaryLocation
    val primaryLocation2 = createPrimaryLocation

    val versionedStore =
      MemoryVersionedStore[ReplicaPath, AggregatorInternalRecord](
        initialEntries = Map.empty
      )

    withAggregator(versionedStore) { aggregator =>
      Seq(primaryLocation1, primaryLocation2)
        .map { storageLocation =>
          createReplicaResultWith(storageLocation = storageLocation)
        }
        .foreach { replicaResult =>
          aggregator.aggregate(replicaResult)
        }
    }

    versionedStore.store
      .asInstanceOf[MemoryStore[Version[ReplicaPath, Int], AggregatorInternalRecord]]
      .entries should have size 2
  }

  it("handles an error from the underlying versioned store") {
    val throwable = new Throwable("BOOM!")

    val brokenStore =
      new MemoryVersionedStore[ReplicaPath, AggregatorInternalRecord](
        store =
          new MemoryStore[Version[ReplicaPath, Int], AggregatorInternalRecord](
            initialEntries = Map.empty
          ) with MemoryMaxima[ReplicaPath, AggregatorInternalRecord]
      ) {
        override def upsert(id: ReplicaPath)(
          t: AggregatorInternalRecord
        )(
          f: AggregatorInternalRecord => AggregatorInternalRecord
        ): UpdateEither =
          Left(UpdateWriteError(throwable))
      }

    val result =
      withAggregator(brokenStore) {
        _.aggregate(createReplicaResult)
      }

    result.failed.get shouldBe throwable
  }

  it("accepts adding the same primary location to a record twice") {
    val primaryLocation = createPrimaryLocation

    val replicaResult = createReplicaResultWith(
      storageLocation = primaryLocation
    )

    val expectedRecord =
      AggregatorInternalRecord(
        location = Some(primaryLocation),
        replicas = List.empty
      )

    withAggregator() { aggregator =>
      (1 to 5).map { _ =>
        aggregator.aggregate(replicaResult).success.value shouldBe expectedRecord
      }
    }
  }

  it("fails if you add different primary locations for the same replica path") {
    val prefix = createObjectLocationPrefix

    val primaryLocation1 = createPrimaryLocationWith(
      prefix = prefix.copy(namespace = randomAlphanumeric)
    )

    val primaryLocation2 = createPrimaryLocationWith(
      prefix = prefix.copy(namespace = randomAlphanumeric)
    )

    val replicaResult1 = createReplicaResultWith(primaryLocation1)
    val replicaResult2 = createReplicaResultWith(primaryLocation2)

    withAggregator() { aggregator =>
      aggregator.aggregate(replicaResult1) shouldBe a[Success[_]]

      val err = aggregator.aggregate(replicaResult2).failed.get
      err.getMessage should startWith("Record already has a different PrimaryStorageLocation")
    }
  }

  it("only stores unique replica results") {
    val replicaResult = createReplicaResult

    val results: Seq[Try[AggregatorInternalRecord]] =
      withAggregator() { aggregator =>
        (1 to 3).map { _ =>
          aggregator.aggregate(replicaResult)
        }
      }

    val uniqResults = results
      .map { _.success.value }
      .toSet

    uniqResults should have size 1

    uniqResults.head shouldBe AggregatorInternalRecord(replicaResult.storageLocation)
  }
}
