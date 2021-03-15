locals {
  wc_user_pool_id = "eu-west-1_h3qKmdYyD"
}

module "alex_glitch_dashboard" {
  source = "../modules/app_client"

  name         = "Alex Glitch dashboard"
  user_pool_id = local.wc_user_pool_id

  allow_bags_access    = false
  allow_ingests_access = true
}

module "catalogue_client" {
  source = "../modules/app_client"

  name         = "Catalogue"
  user_pool_id = local.wc_user_pool_id

  allow_bags_access    = true
  allow_ingests_access = true
}

module "end_to_end_client" {
  source = "../modules/app_client"

  name         = "End-to-end Lambda tester"
  user_pool_id = local.wc_user_pool_id

  allow_bags_access    = true
  allow_ingests_access = true
}

