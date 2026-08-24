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

variable "api_domain" {
  description = "Production API hostname."
  type        = string
}

variable "deploy_runtime" {
  description = "Enable only after secret versions and the DB user are ready."
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
