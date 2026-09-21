variable "project_id" {
  description = "Existing production Google Cloud project ID."
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
  description = "Create the production admin Cloud Run service."
  type        = bool
  default     = false
}

variable "admin_container_image" {
  description = "Production admin image using a commit SHA tag or digest."
  type        = string
  default     = ""
}

variable "admin_api_base_url" {
  description = "HTTPS production API URL used by the admin BFF."
  type        = string
  default     = ""
}

variable "admin_iap_members" {
  description = "Production operators allowed through IAP."
  type        = set(string)
  default     = []
}

variable "api_domain" {
  description = "Production API hostname."
  type        = string
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
  description = "Redis backend selected for production."
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

variable "cors_allowed_origins" {
  description = "Exact production browser origins."
  type        = string
}

variable "smtp_host" {
  description = "Production SMTP hostname."
  type        = string
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
  default     = false
}

variable "admin_email_login_from" {
  description = "Verified sender for administrator email login."
  type        = string
  default     = "support@mypasture.app"
}

variable "mail_from" {
  description = "Verified production sender address."
  type        = string
}

variable "firebase_enabled" {
  description = "Enable production FCM delivery."
  type        = bool
  default     = true
}

variable "firebase_project_id" {
  description = "Firebase project ID."
  type        = string
}

variable "alert_email" {
  description = "Operations email."
  type        = string
}

variable "billing_account_id" {
  description = "Billing account ID used for budget alerts."
  type        = string
}

variable "monthly_budget_amount" {
  description = "Monthly budget amount in the billing account currency."
  type        = number
  default     = 500000
}

variable "budget_currency_code" {
  description = "ISO 4217 currency code matching the billing account currency."
  type        = string
  default     = "KRW"
}

variable "google_oauth_web_client_ids" {
  type = string
}

variable "google_oauth_ios_client_ids" {
  type = string
}

variable "google_oauth_android_client_ids" {
  type = string
}

variable "apple_oauth_client_ids" {
  type = string
}

variable "kakao_app_ids" {
  type = string
}
