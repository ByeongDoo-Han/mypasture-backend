resource "google_cloud_run_v2_service" "admin" {
  count = var.deploy_runtime && var.admin_enabled ? 1 : 0

  project             = var.project_id
  name                = "${local.resource_prefix}-admin"
  location            = var.region
  ingress             = "INGRESS_TRAFFIC_ALL"
  iap_enabled         = true
  deletion_protection = var.environment == "prod"

  template {
    service_account                  = google_service_account.admin.email
    timeout                          = "30s"
    max_instance_request_concurrency = 40

    scaling {
      min_instance_count = var.admin_min_instances
      max_instance_count = var.admin_max_instances
    }

    containers {
      image = var.admin_container_image

      ports {
        container_port = 8080
      }

      resources {
        limits = {
          cpu    = "1"
          memory = "512Mi"
        }
        cpu_idle          = true
        startup_cpu_boost = true
      }

      env {
        name  = "APP_ENV"
        value = "prod"
      }

      env {
        name  = "API_BASE_URL"
        value = trimsuffix(var.admin_api_base_url, "/")
      }

      env {
        name  = "NEXT_TELEMETRY_DISABLED"
        value = "1"
      }

      startup_probe {
        initial_delay_seconds = 0
        timeout_seconds       = 3
        period_seconds        = 3
        failure_threshold     = 20
        http_get {
          path = "/api/health"
          port = 8080
        }
      }

      liveness_probe {
        initial_delay_seconds = 10
        timeout_seconds       = 3
        period_seconds        = 10
        failure_threshold     = 3
        http_get {
          path = "/api/health"
          port = 8080
        }
      }
    }
  }

  depends_on = [google_project_service.required]

  lifecycle {
    ignore_changes = [template[0].containers[0].image]
  }
}

resource "google_cloud_run_v2_service_iam_member" "admin_iap_invoker" {
  count = var.deploy_runtime && var.admin_enabled ? 1 : 0

  project  = var.project_id
  location = var.region
  name     = google_cloud_run_v2_service.admin[0].name
  role     = "roles/run.invoker"
  member   = "serviceAccount:service-${data.google_project.current.number}@gcp-sa-iap.iam.gserviceaccount.com"

  depends_on = [google_project_service.required]
}

resource "google_iap_web_cloud_run_service_iam_member" "admin_access" {
  for_each = var.deploy_runtime && var.admin_enabled ? var.admin_iap_members : toset([])

  project                = var.project_id
  location               = var.region
  cloud_run_service_name = google_cloud_run_v2_service.admin[0].name
  role                   = "roles/iap.httpsResourceAccessor"
  member                 = each.value

  depends_on = [google_cloud_run_v2_service_iam_member.admin_iap_invoker]
}
