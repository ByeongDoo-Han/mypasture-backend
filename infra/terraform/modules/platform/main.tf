locals {
  resource_prefix = "mypasture-${var.environment}"

  required_services = toset([
    "artifactregistry.googleapis.com",
    "billingbudgets.googleapis.com",
    "compute.googleapis.com",
    "iam.googleapis.com",
    "monitoring.googleapis.com",
    "redis.googleapis.com",
    "run.googleapis.com",
    "secretmanager.googleapis.com",
    "servicenetworking.googleapis.com",
    "sqladmin.googleapis.com",
  ])
}

data "google_project" "current" {
  project_id = var.project_id
}

resource "google_project_service" "required" {
  for_each = local.required_services

  project            = var.project_id
  service            = each.value
  disable_on_destroy = false
}

resource "google_artifact_registry_repository" "backend" {
  project       = var.project_id
  location      = var.region
  repository_id = "${local.resource_prefix}-backend"
  description   = "Immutable My Pasture backend images"
  format        = "DOCKER"

  cleanup_policies {
    id     = "delete-untagged"
    action = "DELETE"
    condition {
      tag_state  = "UNTAGGED"
      older_than = "2592000s"
    }
  }

  depends_on = [google_project_service.required]
}

resource "google_service_account" "runtime" {
  project      = var.project_id
  account_id   = "${local.resource_prefix}-runtime"
  display_name = "My Pasture ${var.environment} runtime"

  depends_on = [google_project_service.required]
}

resource "google_project_iam_member" "runtime_roles" {
  for_each = toset([
    "roles/cloudsql.client",
    "roles/logging.logWriter",
    "roles/monitoring.metricWriter",
  ])

  project = var.project_id
  role    = each.value
  member  = "serviceAccount:${google_service_account.runtime.email}"
}
