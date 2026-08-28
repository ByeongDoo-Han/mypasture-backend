variable "identity_project_id" {
  description = "Project that owns the Workload Identity Pool. It may also be an application project."
  type        = string
}

variable "region" {
  description = "Artifact Registry and Cloud Run region."
  type        = string
  default     = "asia-northeast3"
}

variable "github_repository" {
  description = "GitHub repository in owner/name form."
  type        = string
}

variable "github_repository_id" {
  description = "Immutable numeric GitHub repository ID used to prevent repository-name reuse attacks."
  type        = string
}

variable "github_repository_owner_id" {
  description = "Immutable numeric GitHub repository owner ID."
  type        = string
}

variable "allowed_ref" {
  description = "Only workflow tokens for this Git ref may impersonate deploy service accounts."
  type        = string
  default     = "refs/heads/main"
}

variable "deployments" {
  description = "Environment-specific deployment targets and runtime identities."
  type = map(object({
    project_id                    = string
    artifact_repository_id        = string
    runtime_service_account_email = string
  }))

  validation {
    condition     = length(var.deployments) > 0 && alltrue([for environment in keys(var.deployments) : contains(["staging", "prod"], environment)])
    error_message = "deployments must contain only staging and/or prod targets."
  }
}
