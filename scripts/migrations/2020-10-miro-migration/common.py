#!/usr/bin/env python

import functools
import json

import boto3

sts_client = boto3.client("sts")


@functools.lru_cache
def get_aws_client(resource, *, role_arn):
    assumed_role_object = sts_client.assume_role(
        RoleArn=role_arn, RoleSessionName="AssumeRoleSession1"
    )
    credentials = assumed_role_object["Credentials"]
    return boto3.client(
        resource,
        aws_access_key_id=credentials["AccessKeyId"],
        aws_secret_access_key=credentials["SecretAccessKey"],
        aws_session_token=credentials["SessionToken"],
    )


def get_secret(role_arn, secret_id):
    secretsmanager_client = get_aws_client(resource="secretsmanager", role_arn=role_arn)

    response = secretsmanager_client.get_secret_value(SecretId=secret_id)

    try:
        # The secret response may be a JSON string of the form
        # {"username": "…", "password": "…", "endpoint": "…"}
        secret = json.loads(response["SecretString"])
    except ValueError:
        secret = response["SecretString"]

    return secret


def file_exists(file_location, expected_content_length):
    """
    Check that a file has the expected content length in bytes
    """
    local_content_length = os.path.getsize(file_location)

    assert local_content_length > 0, "Content length is zero: " f"{file_location}"

    assert local_content_length == expected_content_length, (
        "Content length mismatch "
        f"({local_content_length} != {expected_content_length}): "
        f"{file_location}"
    )