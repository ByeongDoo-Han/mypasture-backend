module "platform" {
  source = "../../modules/platform"

  project_id                         = var.project_id
  region                             = var.region
  environment                        = "staging"
  container_image                    = var.container_image
  deploy_runtime                     = var.deploy_runtime
  deploy_services                    = var.deploy_services
  database_tier                      = "db-custom-1-3840"
  database_edition                   = "ENTERPRISE"
  database_availability_type         = "ZONAL"
  database_deletion_protection       = false
  redis_backend                      = var.redis_backend
  provision_redis_vm                 = var.provision_redis_vm
  retain_memorystore                 = var.retain_memorystore
  redis_container_image              = "mirror.gcr.io/library/redis:7.2-alpine"
  redis_tier                         = "BASIC"
  redis_memory_size_gb               = 1
  api_min_instances                  = 0
  api_max_instances                  = 3
  cors_allowed_origins               = var.cors_allowed_origins
  password_reset_url                 = "https://staging.mypasture.app/password-reset"
  quiet_time_invite_base_url         = "https://staging.mypasture.app/q/qt"
  smtp_host                          = var.smtp_host
  mail_from                          = var.mail_from
  email_verification_delivery        = "log"
  password_reset_delivery            = "log"
  daily_word_incident_email_delivery = "disabled"
  firebase_enabled                   = var.firebase_enabled
  firebase_project_id                = var.firebase_project_id
  google_oauth_web_client_ids        = var.google_oauth_web_client_ids
  google_oauth_ios_client_ids        = var.google_oauth_ios_client_ids
  google_oauth_android_client_ids    = var.google_oauth_android_client_ids
  apple_oauth_client_ids             = var.apple_oauth_client_ids
  kakao_app_ids                      = var.kakao_app_ids
  alert_email                        = var.alert_email
  billing_account_id                 = var.billing_account_id
  monthly_budget_amount              = var.monthly_budget_amount
  budget_currency_code               = var.budget_currency_code
}

output "deployment" {
  description = "Staging deployment endpoints and bootstrap values."
  value = {
    artifact_registry = module.platform.artifact_registry_repository
    api_url           = module.platform.api_url
    migration_job     = module.platform.migration_job_name
    embedding_job     = module.platform.embedding_backfill_job_name
    secret_ids        = module.platform.runtime_secret_ids
  }
}
