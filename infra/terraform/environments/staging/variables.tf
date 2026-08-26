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

variable "cors_allowed_origins" {
  description = "Exact staging browser origins."
  type        = string
}

variable "smtp_host" {
  description = "Staging SMTP hostname."
  type        = string
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
