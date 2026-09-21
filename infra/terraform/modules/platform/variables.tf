variable "project_id" {
  description = "Existing Google Cloud project ID for this environment."
  type        = string
}

variable "region" {
  description = "Region shared by Cloud Run, Cloud SQL, and Memorystore."
  type        = string
  default     = "asia-northeast3"
}

variable "environment" {
  description = "Deployment environment name."
  type        = string

  validation {
    condition     = contains(["staging", "prod"], var.environment)
    error_message = "environment must be staging or prod."
  }
}

variable "container_image" {
  description = "Immutable Artifact Registry image reference including a digest or commit SHA tag."
  type        = string
}

variable "admin_enabled" {
  description = "Create the IAP-protected Next.js admin Cloud Run service."
  type        = bool
  default     = false
}

variable "admin_container_image" {
  description = "Immutable admin image reference including a digest or commit SHA tag."
  type        = string
  default     = ""

  validation {
    condition     = !var.admin_enabled || trimspace(var.admin_container_image) != ""
    error_message = "admin_container_image is required when admin_enabled is true."
  }
}

variable "admin_api_base_url" {
  description = "HTTPS backend base URL used only by the server-side admin BFF."
  type        = string
  default     = ""

  validation {
    condition     = !var.admin_enabled || can(regex("^https://[^/]+", var.admin_api_base_url))
    error_message = "admin_api_base_url must be an HTTPS URL when admin_enabled is true."
  }
}

variable "admin_iap_members" {
  description = "Users or groups allowed through IAP, such as user:operator@example.com."
  type        = set(string)
  default     = []

  validation {
    condition = alltrue([
      for member in var.admin_iap_members : can(regex("^(user|group):[^[:space:]]+$", member))
    ])
    error_message = "admin_iap_members accepts only user: or group: principals."
  }
}

variable "admin_min_instances" {
  description = "Warm admin instances. Keep zero until cold-start latency becomes operationally costly."
  type        = number
  default     = 0

  validation {
    condition     = var.admin_min_instances >= 0 && floor(var.admin_min_instances) == var.admin_min_instances
    error_message = "admin_min_instances must be a non-negative integer."
  }
}

variable "admin_max_instances" {
  description = "Maximum admin instances used to cap cost and backend load."
  type        = number
  default     = 2

  validation {
    condition     = var.admin_max_instances >= var.admin_min_instances && floor(var.admin_max_instances) == var.admin_max_instances
    error_message = "admin_max_instances must be an integer greater than or equal to admin_min_instances."
  }
}

variable "enable_external_load_balancer" {
  description = "Expose the API through an external HTTPS load balancer and Cloud Armor."
  type        = bool
  default     = false
}

variable "api_domain" {
  description = "API hostname used by the managed TLS certificate."
  type        = string
  default     = ""
}

variable "deploy_runtime" {
  description = "Create Cloud Run resources after all required secret versions and the DB user exist."
  type        = bool
  default     = false
}

variable "deploy_services" {
  description = "Create API and worker only after the migration job succeeds."
  type        = bool
  default     = false
}

variable "database_name" {
  description = "Application database name."
  type        = string
  default     = "mypasture"
}

variable "database_username" {
  description = "Application DB user created outside Terraform so its password never enters Terraform state."
  type        = string
  default     = "mypasture_app"
}

variable "migration_database_username" {
  description = "Migration-only DB user created outside Terraform."
  type        = string
  default     = "mypasture_migrator"
}

variable "database_tier" {
  description = "Cloud SQL machine tier."
  type        = string
}

variable "database_edition" {
  description = "Cloud SQL edition compatible with the selected machine tier."
  type        = string
  default     = "ENTERPRISE"

  validation {
    condition     = contains(["ENTERPRISE", "ENTERPRISE_PLUS"], var.database_edition)
    error_message = "database_edition must be ENTERPRISE or ENTERPRISE_PLUS."
  }
}

variable "database_availability_type" {
  description = "ZONAL for staging or REGIONAL for production HA."
  type        = string
}

variable "database_disk_size_gb" {
  description = "Initial Cloud SQL SSD size."
  type        = number
  default     = 20
}

variable "database_deletion_protection" {
  description = "Protect the Cloud SQL instance from accidental deletion."
  type        = bool
  default     = true
}

variable "redis_tier" {
  description = "BASIC for staging or STANDARD_HA for production."
  type        = string
}

variable "redis_backend" {
  description = "Redis runtime backend. Use vm for a private Docker host or memorystore during migration."
  type        = string
  default     = "vm"

  validation {
    condition     = contains(["vm", "memorystore"], var.redis_backend)
    error_message = "redis_backend must be vm or memorystore."
  }
}

variable "provision_redis_vm" {
  description = "Create the Redis VM alongside Memorystore for a verified migration."
  type        = bool
  default     = false
}

variable "retain_memorystore" {
  description = "Temporarily retain Memorystore after the active backend switches to the VM."
  type        = bool
  default     = false
}

variable "redis_memory_size_gb" {
  description = "Memorystore capacity in GiB."
  type        = number
  default     = 1
}

variable "redis_auth_enabled" {
  description = "Enable Redis AUTH. The generated value must be copied to Secret Manager outside Terraform."
  type        = bool
  default     = true
}

variable "redis_zone" {
  description = "Compute Engine zone for the Redis VM."
  type        = string
  default     = "asia-northeast3-a"
}

variable "redis_machine_type" {
  description = "Compute Engine machine type for the Redis VM."
  type        = string
  default     = "e2-small"
}

variable "redis_maxmemory_mb" {
  description = "Redis data memory limit in MiB. Leave headroom for the OS, Docker, TLS, and AOF rewrites."
  type        = number
  default     = 1024

  validation {
    condition     = var.redis_maxmemory_mb >= 64 && floor(var.redis_maxmemory_mb) == var.redis_maxmemory_mb
    error_message = "redis_maxmemory_mb must be an integer of at least 64 MiB."
  }
}

variable "redis_maxclients" {
  description = "Maximum simultaneous Redis clients. Bound this to protect small VM memory."
  type        = number
  default     = 10000

  validation {
    condition     = var.redis_maxclients >= 100 && var.redis_maxclients <= 10000 && floor(var.redis_maxclients) == var.redis_maxclients
    error_message = "redis_maxclients must be an integer between 100 and 10000."
  }
}

variable "redis_internal_ip" {
  description = "Reserved private IPv4 address for the Redis VM."
  type        = string
  default     = "10.42.0.10"
}

variable "redis_container_image" {
  description = "Redis image pulled by Container-Optimized OS. Pin a digest for production."
  type        = string
  default     = "mirror.gcr.io/library/redis@sha256:ccd6aa8d45ff3f033d6fa15b8cc1a50579f65c89f38cf9bb607a954c4f2128ed"
}

variable "redis_boot_disk_size_gb" {
  description = "Redis VM boot disk size. AOF data is stored on the stateful partition."
  type        = number
  default     = 20
}

variable "api_min_instances" {
  description = "Warm Cloud Run API instances."
  type        = number
}

variable "api_max_instances" {
  description = "Maximum API instances, capped to protect DB connections and cost."
  type        = number
}

variable "worker_enabled" {
  description = "Create the singleton scheduled worker service."
  type        = bool
  default     = true
}

variable "worker_enable_embedding_indexing" {
  description = "Enable continuous paid embedding indexing in the singleton worker. Initial backfill uses a separate job."
  type        = bool
  default     = false
}

variable "worker_enable_commentary_generation" {
  description = "Enable paid chapter commentary generation in the singleton worker."
  type        = bool
  default     = false
}

variable "cors_allowed_origins" {
  description = "Comma-separated exact browser origins."
  type        = string
}

variable "password_reset_url" {
  description = "Public password reset deep link or HTTPS URL."
  type        = string
}

variable "quiet_time_invite_base_url" {
  description = "Public QT invitation URL prefix."
  type        = string
}

variable "smtp_host" {
  description = "SMTP provider hostname."
  type        = string
}

variable "smtp_port" {
  description = "SMTP provider port."
  type        = number
  default     = 587
}

variable "smtp_starttls_enabled" {
  description = "Require SMTP STARTTLS (normally port 587)."
  type        = bool
  default     = true
}

variable "smtp_ssl_enabled" {
  description = "Use implicit SMTP TLS (normally port 465)."
  type        = bool
  default     = false
}

variable "admin_email_login_enabled" {
  description = "Enable administrator email OTP on the API only after SMTP and secret versions are ready."
  type        = bool
  default     = false

  validation {
    condition     = !var.admin_email_login_enabled || var.smtp_starttls_enabled || var.smtp_ssl_enabled
    error_message = "Administrator email login requires STARTTLS or implicit SMTP TLS."
  }
}

variable "admin_email_login_from" {
  description = "Verified sender address for administrator email login."
  type        = string
  default     = "support@mypasture.app"
}

variable "mail_from" {
  description = "Verified sender address."
  type        = string
}

variable "email_verification_delivery" {
  description = "Email verification delivery adapter. Use log only outside production."
  type        = string
  default     = "smtp"

  validation {
    condition     = contains(["smtp", "log"], var.email_verification_delivery)
    error_message = "email_verification_delivery must be smtp or log."
  }
}

variable "password_reset_delivery" {
  description = "Password reset delivery adapter. Use log only outside production."
  type        = string
  default     = "smtp"

  validation {
    condition     = contains(["smtp", "log"], var.password_reset_delivery)
    error_message = "password_reset_delivery must be smtp or log."
  }
}

variable "daily_word_incident_email_delivery" {
  description = "Daily word incident email adapter."
  type        = string
  default     = "smtp"

  validation {
    condition     = contains(["smtp", "log", "disabled"], var.daily_word_incident_email_delivery)
    error_message = "daily_word_incident_email_delivery must be smtp, log, or disabled."
  }
}

variable "firebase_enabled" {
  description = "Mount and use the Firebase service account on the worker."
  type        = bool
  default     = false
}

variable "firebase_project_id" {
  description = "Firebase project ID used by FCM."
  type        = string
  default     = ""
}

variable "google_oauth_web_client_ids" {
  description = "Comma-separated Google web OAuth client IDs."
  type        = string
  default     = ""
}

variable "google_oauth_ios_client_ids" {
  description = "Comma-separated Google iOS OAuth client IDs."
  type        = string
  default     = ""
}

variable "google_oauth_android_client_ids" {
  description = "Comma-separated Google Android OAuth client IDs."
  type        = string
  default     = ""
}

variable "apple_oauth_client_ids" {
  description = "Comma-separated Apple OAuth client IDs."
  type        = string
  default     = ""
}

variable "kakao_app_ids" {
  description = "Comma-separated Kakao numeric application IDs."
  type        = string
  default     = ""
}

variable "alert_email" {
  description = "Optional Cloud Monitoring notification email."
  type        = string
  default     = ""
}

variable "billing_account_id" {
  description = "Optional billing account ID used to create a monthly budget."
  type        = string
  default     = ""
}

variable "monthly_budget_amount" {
  description = "Monthly budget alert amount in the billing account currency."
  type        = number
  default     = 100
}

variable "budget_currency_code" {
  description = "ISO 4217 currency code matching the billing account currency."
  type        = string
  default     = "USD"
}
