locals {
  pool_id     = "mypasture-github-actions"
  provider_id = "mypasture-backend"
  repository_principal = format(
    "principalSet://iam.googleapis.com/%s/attribute.repository_id/%s",
    google_iam_workload_identity_pool.github.name,
    var.github_repository_id,
  )
}

resource "google_project_service" "identity_apis" {
  for_each = toset([
    "iam.googleapis.com",
    "iamcredentials.googleapis.com",
    "sts.googleapis.com",
  ])

  project            = var.identity_project_id
  service            = each.value
  disable_on_destroy = false
}

resource "google_iam_workload_identity_pool" "github" {
  project                   = var.identity_project_id
  workload_identity_pool_id = local.pool_id
  display_name              = "My Pasture GitHub Actions"
  description               = "OIDC trust for ${var.github_repository} deployments"
  disabled                  = false

  depends_on = [google_project_service.identity_apis]

  lifecycle {
    prevent_destroy = true
  }
}

resource "google_iam_workload_identity_pool_provider" "github" {
  project                            = var.identity_project_id
  workload_identity_pool_id          = google_iam_workload_identity_pool.github.workload_identity_pool_id
  workload_identity_pool_provider_id = local.provider_id
  display_name                       = "My Pasture backend"
  description                        = "Accepts main-branch GitHub OIDC tokens from one immutable repository ID"

  attribute_mapping = {
    "google.subject"                = "assertion.sub"
    "attribute.repository"          = "assertion.repository"
    "attribute.repository_id"       = "assertion.repository_id"
    "attribute.repository_owner_id" = "assertion.repository_owner_id"
    "attribute.ref"                 = "assertion.ref"
  }

  attribute_condition = join(" && ", [
    "assertion.repository == '${var.github_repository}'",
    "assertion.repository_id == '${var.github_repository_id}'",
    "assertion.repository_owner_id == '${var.github_repository_owner_id}'",
    "assertion.ref == '${var.allowed_ref}'",
  ])

  oidc {
    issuer_uri = "https://token.actions.githubusercontent.com"
  }

  lifecycle {
    prevent_destroy = true
  }
}

resource "google_service_account" "deploy" {
  for_each = var.deployments

  project      = each.value.project_id
  account_id   = "mypasture-${each.key}-deploy"
  display_name = "My Pasture ${each.key} GitHub deployer"
  description  = "Keyless deployment identity for ${var.github_repository}"

  lifecycle {
    prevent_destroy = true
  }
}

resource "google_service_account_iam_member" "github_impersonation" {
  for_each = google_service_account.deploy

  service_account_id = each.value.name
  role               = "roles/iam.workloadIdentityUser"
  member             = local.repository_principal
}

resource "google_project_iam_member" "cloud_run_developer" {
  for_each = var.deployments

  project = each.value.project_id
  role    = "roles/run.developer"
  member  = "serviceAccount:${google_service_account.deploy[each.key].email}"
}

resource "google_artifact_registry_repository_iam_member" "artifact_writer" {
  for_each = var.deployments

  project    = each.value.project_id
  location   = var.region
  repository = each.value.artifact_repository_id
  role       = "roles/artifactregistry.writer"
  member     = "serviceAccount:${google_service_account.deploy[each.key].email}"
}

resource "google_service_account_iam_member" "runtime_act_as" {
  for_each = var.deployments

  service_account_id = "projects/${each.value.project_id}/serviceAccounts/${each.value.runtime_service_account_email}"
  role               = "roles/iam.serviceAccountUser"
  member             = "serviceAccount:${google_service_account.deploy[each.key].email}"
}
