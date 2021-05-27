locals {
  default_tags = {
    TerraformConfigurationURL = "https://github.com/wellcomecollection/storage-service/tree/main/terraform/stack_prod"
  }
}

provider "aws" {
  assume_role {
    role_arn = "arn:aws:iam::975596993436:role/storage-admin"
  }

  region = var.aws_region

  default_tags {
    tags = local.default_tags
  }

  # Ignore deployment tags on services
  ignore_tags {
    keys = ["deployment:label"]
  }
}

