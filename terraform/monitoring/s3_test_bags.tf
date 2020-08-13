# Upload a series of test bags to an S3 bucket.  We manage the bags in Git and
# Terraform rather than by hand, so we can track changes and know what version
# of a test bag was in use at a given time.

locals {
  infra_bucket    = "wellcomecollection-storage-infra"
  test_bag_prefix = "test_bags/"
}

# Give the unpacker tasks permission to read all of the test bags.

data "aws_iam_policy_document" "read_test_bags" {
  statement {
    actions = [
      "s3:GetObject",
    ]

    resources = [
      "arn:aws:s3:::${local.infra_bucket}/${local.test_bag_prefix}*"
    ]
  }
}

resource "aws_iam_role_policy" "allow_staging_read_test_bags" {
  role   = data.terraform_remote_state.stack_staging.outputs.unpacker_task_role_name
  policy = data.aws_iam_policy_document.read_test_bags.json
}

resource "aws_iam_role_policy" "allow_prod_read_test_bags" {
  role   = data.terraform_remote_state.stack_prod.outputs.unpacker_task_role_name
  policy = data.aws_iam_policy_document.read_test_bags.json
}
