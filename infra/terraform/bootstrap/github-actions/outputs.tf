output "workload_identity_provider" {
  description = "Set this as GCP_WORKLOAD_IDENTITY_PROVIDER in each GitHub Environment."
  value       = google_iam_workload_identity_pool_provider.github.name
}

output "deploy_service_accounts" {
  description = "Environment-specific deploy identities for GCP_DEPLOY_SERVICE_ACCOUNT."
  value       = { for environment, account in google_service_account.deploy : environment => account.email }
}

output "admin_workload_identity_provider" {
  description = "Set this as GCP_WORKLOAD_IDENTITY_PROVIDER in the admin GitHub Environments."
  value       = try(google_iam_workload_identity_pool_provider.admin[0].name, "")
}

output "admin_deploy_service_accounts" {
  description = "Environment-specific admin deployment identities."
  value       = { for environment, account in google_service_account.admin_deploy : environment => account.email }
}

output "github_environment_variables" {
  description = "Non-secret values to register in the matching GitHub Environment."
  value = {
    for environment, target in var.deployments : environment => {
      GCP_PROJECT_ID                 = target.project_id
      GCP_REGION                     = var.region
      GCP_ARTIFACT_REPOSITORY        = target.artifact_repository_id
      GCP_WORKLOAD_IDENTITY_PROVIDER = google_iam_workload_identity_pool_provider.github.name
      GCP_DEPLOY_SERVICE_ACCOUNT     = google_service_account.deploy[environment].email
    }
  }
}

output "admin_github_environment_variables" {
  description = "Non-secret values to register in matching mypasture-admin GitHub Environments."
  value = {
    for environment, target in var.admin_deployments : environment => {
      GCP_PROJECT_ID                 = target.project_id
      GCP_REGION                     = var.region
      GCP_ARTIFACT_REPOSITORY        = target.artifact_repository_id
      GCP_WORKLOAD_IDENTITY_PROVIDER = google_iam_workload_identity_pool_provider.admin[0].name
      GCP_DEPLOY_SERVICE_ACCOUNT     = google_service_account.admin_deploy[environment].email
      GCP_DEPLOY_ENABLED             = "false"
    }
  }
}
