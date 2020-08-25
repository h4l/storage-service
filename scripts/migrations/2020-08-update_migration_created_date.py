#!/usr/bin/env python
import json
from common import get_aws_resource, scan_table

dynamodb = get_aws_resource("dynamodb")
s3 = get_aws_resource("s3")

vhs_table = "vhs-storage-staging-manifests-2020-07-24"

backfill_vhs_table = "vhs-storage-staging-manifests-2020-08-19"

def get_bucket_key(item):
    try:
        bucket = item["payload"]["namespace"]
        key = item["payload"]["path"]
    except KeyError:
        try:
            bucket = item['payload']['bucket']
            key = item['payload']['key']
        except:
            raise RuntimeError(f"Find s3 bucket, key in {item}")
        else:
            return bucket, key
    else:
        return bucket, key


def get_vhs_json(bucket, key):
    return json.loads(s3.Object(bucket, key).get()['Body'].read().decode('utf-8'))

def put_vhs_json(bucket, key, content):
    s3object = s3.Object(bucket,key)
    # s3object.put(
    #     Body=(bytes(json.dumps(content).encode('UTF-8')))
    # )

for item in scan_table(TableName=vhs_table):
    id = item["id"]
    version = item["version"]
    bucket, key = get_bucket_key(item)
    try:
        vhs_content=get_vhs_json(bucket, key)
    except:
        raise RuntimeError(f"Cannot read s3 entry for {id}: {item}")
    else:
        created_date = vhs_content['createdDate']
        try:
            backfilled_item = dynamodb.Table(backfill_vhs_table).get_item(Key={"id": id, "version": version})[
                "Item"
            ]
        except KeyError:
            raise RuntimeError(f"Cannot find backfill storage manifest for {id}!!!!")
        else:
            backfilled_bucket = backfilled_item['payload']['bucket']
            backfilled_key = backfilled_item['payload']['key']
            backfilled_json = get_vhs_json(backfilled_bucket, backfilled_key)
            backfilled_json['createdDate'] = created_date

