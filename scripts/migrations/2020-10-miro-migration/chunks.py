#!/usr/bin/env python3
"""
Functions related to gathering migration chunk information
for creating transfer packages
"""

import collections

import attr
import click
import elasticsearch

from decisions import Decision
from elastic_helpers import get_local_elastic_client
from iter_helpers import chunked_iterable


@attr.s
class Chunk:
    group_name = attr.ib()
    destination = attr.ib()
    s3_keys = attr.ib(default=list)
    transfer_package = attr.ib(default=None)
    total_size = attr.ib(default=None)

    def merge_chunk(self, other):
        assert other.chunk_id() == self.chunk_id()
        self.s3_keys = self.s3_keys + other.s3_keys

    def chunk_id(self):
        return f"{self.destination}/{self.group_name}"

    def is_uploaded(self):
        if self.transfer_package:
            if self.transfer_package.s3_location:
                return True

        return False


def gather_chunks(decisions_index):
    local_elastic_client = get_local_elastic_client()

    query_body = {"query": {"bool": {"must_not": [{"term": {"skip": True}}]}}}

    total_chunkable_decisions = local_elastic_client.count(
        body=query_body, index=decisions_index
    )["count"]

    chunkable_decisions = elasticsearch.helpers.scan(
        local_elastic_client, query=query_body, index=decisions_index
    )

    click.echo(f"Gathering chunks from {total_chunkable_decisions} decisions.")

    # Dict (shard, destination) -> list(s3 keys)
    groups = collections.defaultdict(list)

    for result in chunkable_decisions:
        decision = Decision(**result["_source"])
        for destination in decision.destinations:
            groups[(decision.group_name, destination)].append(decision)

    click.echo(f"Found {len(groups)} chunks.")

    def _build_chunks(group_name, destination, decisions):
        total_size = 0
        sum_total_size = 0

        s3_keys = []
        chunked_s3_keys = []

        for decision in decisions:
            s3_keys.append(decision.s3_key)
            total_size = total_size + decision.s3_size
            sum_total_size = sum_total_size + decision.s3_size

            if total_size > 15_000_000_000:
                chunked_s3_keys.append({"s3_keys": s3_keys, "total_size": total_size})
                s3_keys = []
                total_size = 0

        chunked_s3_keys.append({"s3_keys": s3_keys, "total_size": total_size})

        chunks = []
        for i, s3_keys_chunk in enumerate(chunked_s3_keys):
            if len(chunked_s3_keys) > 1:
                new_group_name = f"{group_name}_{i+1}"
            else:
                new_group_name = group_name

            chunks.append(
                Chunk(
                    group_name=new_group_name,
                    destination=destination,
                    s3_keys=s3_keys_chunk["s3_keys"],
                    total_size=s3_keys_chunk["total_size"],
                )
            )

        actual_total_files = 0
        actual_total_size = 0

        for chunk in chunks:
            actual_total_files = actual_total_files + len(chunk.s3_keys)
            actual_total_size = actual_total_size + chunk.total_size

        assert (
            sum_total_size == actual_total_size
        ), f"Total size mismatch ({sum_total_size} == {actual_total_size})"

        assert (
            len(decisions) == actual_total_files
        ), f"Total file count mismatch ({len(decisions)} == {actual_total_files})"

        return chunks

    chunks_list = [
        _build_chunks(
            group_name=group_name, destination=destination, decisions=decisions
        )
        for (group_name, destination), decisions in groups.items()
    ]

    return [item for sublist in chunks_list for item in sublist]


if __name__ == "__main__":
    tally = collections.Counter()

    for chunk in gather_chunks("decisions"):
        tally[chunk.chunk_id()] = len(chunk.s3_keys)

    from pprint import pprint

    pprint(tally.items())