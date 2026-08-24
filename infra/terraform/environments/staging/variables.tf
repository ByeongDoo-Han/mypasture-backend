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
