variable "project_id" {
  description = "Existing staging Google Cloud project ID."
  type        = string
}

variable "region" {
  description = "Google Cloud region."
  type        = string
  default     = "asia-northeast3"
}

variable "container_image" {
  description = "Backend image using a commit SHA tag or digest."
  type        = string
}

variable "admin_enabled" {
  description = "Create the staging admin Cloud Run service."
  type        = bool
  default     = false
}

variable "admin_container_image" {
  description = "Staging admin image using a commit SHA tag or digest."
  type        = string
  default     = ""
}

variable "admin_api_base_url" {
  description = "HTTPS staging API URL used by the admin BFF."
  type        = string
  default     = ""
}

variable "admin_iap_members" {
  description = "Staging operators allowed through IAP."
  type        = set(string)
  default     = []
}

variable "enable_external_load_balancer" {
  description = "Expose the staging API through an external HTTPS load balancer and Cloud Armor."
  type        = bool
  default     = false
}

variable "api_domain" {
  description = "Staging API hostname used by the Google-managed TLS certificate."
  type        = string
  default     = ""
}

variable "deploy_runtime" {
  description = "Enable only after secret versions and the DB user are ready."
  type        = bool
  default     = false
}

variable "deploy_services" {
  description = "Enable API and worker only after the migration job succeeds."
  type        = bool
  default     = false
}

variable "redis_backend" {
  description = "Redis backend selected for staging migration."
  type        = string
  default     = "vm"

  validation {
    condition     = contains(["vm", "memorystore"], var.redis_backend)
    error_message = "redis_backend must be vm or memorystore."
  }
}

variable "provision_redis_vm" {
  description = "Create the Redis VM before switching the active backend."
  type        = bool
  default     = false
}

variable "retain_memorystore" {
  description = "Retain Memorystore until the VM backend passes migration checks."
  type        = bool
  default     = false
}

variable "redis_machine_type" {
  description = "Staging Redis VM machine type."
  type        = string
  default     = "e2-micro"
}

variable "redis_maxmemory_mb" {
  description = "Staging Redis data memory limit in MiB."
  type        = number
  default     = 384
}

variable "redis_maxclients" {
  description = "Staging Redis maximum simultaneous clients."
  type        = number
  default     = 500
}

variable "cors_allowed_origins" {
  description = "Exact staging browser origins."
  type        = string
}

variable "smtp_host" {
  description = "SMTP hostname for the staging-named deployment serving the public API."
  type        = string
  default     = "smtp.resend.com"
}

variable "smtp_port" {
  description = "SMTP provider port."
  type        = number
  default     = 587
}

variable "smtp_starttls_enabled" {
  description = "Use SMTP STARTTLS."
  type        = bool
  default     = true
}

variable "smtp_ssl_enabled" {
  description = "Use implicit SMTP TLS."
  type        = bool
  default     = false
}

variable "admin_email_login_enabled" {
  description = "Enable administrator email login on the API after importing and populating its secrets."
  type        = bool
  default     = true
}

variable "admin_email_login_from" {
  description = "Verified sender for administrator email login."
  type        = string
  default     = "support@mypasture.app"
}

variable "mail_from" {
  description = "Verified staging sender address."
  type        = string
}

variable "firebase_enabled" {
  description = "Enable real staging FCM delivery."
  type        = bool
  default     = false
}

variable "firebase_project_id" {
  description = "Firebase project ID."
  type        = string
  default     = ""
}

variable "alert_email" {
  description = "Optional operations email."
  type        = string
  default     = ""
}

variable "billing_account_id" {
  description = "Optional billing account ID."
  type        = string
  default     = ""
}

variable "monthly_budget_amount" {
  description = "Monthly budget amount in the billing account currency."
  type        = number
  default     = 150000
}

variable "budget_currency_code" {
  description = "ISO 4217 currency code matching the billing account currency."
  type        = string
  default     = "KRW"
}

variable "google_oauth_web_client_ids" {
  type    = string
  default = ""
}

variable "google_oauth_ios_client_ids" {
  type    = string
  default = ""
}

variable "google_oauth_android_client_ids" {
  type    = string
  default = ""
}

variable "apple_oauth_client_ids" {
  type    = string
  default = ""
}

variable "kakao_app_ids" {
  type    = string
  default = ""
}
