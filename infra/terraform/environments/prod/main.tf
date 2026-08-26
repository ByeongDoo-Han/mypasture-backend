module "platform" {
  source = "../../modules/platform"

  project_id                      = var.project_id
  region                          = var.region
  environment                     = "prod"
  container_image                 = var.container_image
  deploy_runtime                  = var.deploy_runtime
  enable_external_load_balancer   = true
  api_domain                      = var.api_domain
  database_tier                   = "db-custom-2-7680"
  database_edition                = "ENTERPRISE"
  database_availability_type      = "REGIONAL"
  database_disk_size_gb           = 50
  database_deletion_protection    = true
  redis_backend                   = var.redis_backend
  provision_redis_vm              = var.provision_redis_vm
  retain_memorystore              = var.retain_memorystore
  redis_tier                      = "STANDARD_HA"
  redis_memory_size_gb            = 1
  api_min_instances               = 1
  api_max_instances               = 10
  cors_allowed_origins            = var.cors_allowed_origins
  password_reset_url              = "https://mypasture.app/password-reset"
  quiet_time_invite_base_url      = "https://mypasture.app/q/qt"
  smtp_host                       = var.smtp_host
  mail_from                       = var.mail_from
  firebase_enabled                = var.firebase_enabled
  firebase_project_id             = var.firebase_project_id
  google_oauth_web_client_ids     = var.google_oauth_web_client_ids
  google_oauth_ios_client_ids     = var.google_oauth_ios_client_ids
  google_oauth_android_client_ids = var.google_oauth_android_client_ids
  apple_oauth_client_ids          = var.apple_oauth_client_ids
  kakao_app_ids                   = var.kakao_app_ids
  alert_email                     = var.alert_email
  billing_account_id              = var.billing_account_id
  monthly_budget_amount           = var.monthly_budget_amount
  budget_currency_code            = var.budget_currency_code
}

output "deployment" {
  description = "Production deployment endpoints and bootstrap values."
  value = {
    artifact_registry = module.platform.artifact_registry_repository
    api_url           = module.platform.api_url
    migration_job     = module.platform.migration_job_name
    load_balancer_ip  = module.platform.load_balancer_ip
    secret_ids        = module.platform.runtime_secret_ids
  }
}
