output "artifact_registry_repository" {
  description = "Artifact Registry repository path without an image name."
  value       = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.backend.repository_id}"
}

output "admin_artifact_registry_repository" {
  description = "Admin Artifact Registry repository path without an image name."
  value       = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.admin.repository_id}"
}

output "api_url" {
  description = "Cloud Run API URL. Empty until deploy_runtime is enabled."
  value       = try(google_cloud_run_v2_service.api[0].uri, "")
}

output "admin_url" {
  description = "IAP-protected Cloud Run admin URL. Empty until the service is enabled."
  value       = try(google_cloud_run_v2_service.admin[0].uri, "")
}

output "admin_service_name" {
  description = "Admin Cloud Run service name used by the deployment workflow."
  value       = try(google_cloud_run_v2_service.admin[0].name, "")
}

output "admin_runtime_service_account_email" {
  description = "Least-privilege service identity used by the admin BFF."
  value       = google_service_account.admin.email
}

output "load_balancer_ip" {
  description = "Create an A record for api_domain with this IP."
  value       = try(google_compute_global_address.api[0].address, "")
}

output "migration_job_name" {
  description = "Cloud Run migration job name."
  value       = try(google_cloud_run_v2_job.migration[0].name, "")
}

output "embedding_backfill_job_name" {
  description = "One-off Cloud Run job that completes the Bible pgvector index."
  value       = try(google_cloud_run_v2_job.embedding_backfill[0].name, "")
}

output "database_private_ip" {
  description = "Cloud SQL private IP used by the runtime."
  value       = google_sql_database_instance.main.private_ip_address
}

output "redis_host" {
  description = "Private Redis endpoint selected by redis_backend."
  value       = local.redis_host
}

output "runtime_secret_ids" {
  description = "Secret containers that must receive values before runtime deployment."
  value       = { for key, secret in google_secret_manager_secret.runtime : key => secret.secret_id }
}
