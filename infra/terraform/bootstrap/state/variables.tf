variable "project_id" {
  description = "Infrastructure administration project ID."
  type        = string
}

variable "region" {
  description = "State bucket location."
  type        = string
  default     = "asia-northeast3"
}

variable "bucket_name" {
  description = "Globally unique GCS state bucket name."
  type        = string
}
