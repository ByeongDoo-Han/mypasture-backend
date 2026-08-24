locals {
  load_balancer_enabled = var.enable_external_load_balancer && var.deploy_runtime
}

check "load_balancer_inputs" {
  assert {
    condition     = !var.enable_external_load_balancer || var.api_domain != ""
    error_message = "The external load balancer requires a non-empty api_domain."
  }
}

resource "google_compute_global_address" "api" {
  count = local.load_balancer_enabled ? 1 : 0

  project = var.project_id
  name    = "${local.resource_prefix}-api"
}

resource "google_compute_region_network_endpoint_group" "api" {
  count = local.load_balancer_enabled ? 1 : 0

  project               = var.project_id
  name                  = "${local.resource_prefix}-api"
  region                = var.region
  network_endpoint_type = "SERVERLESS"

  cloud_run {
    service = google_cloud_run_v2_service.api[0].name
  }
}

resource "google_compute_security_policy" "api" {
  count = local.load_balancer_enabled ? 1 : 0

  project     = var.project_id
  name        = "${local.resource_prefix}-api"
  description = "Baseline Cloud Armor policy; application auth and AI limits remain authoritative."

  rule {
    action   = "throttle"
    priority = 1000
    match {
      versioned_expr = "SRC_IPS_V1"
      config {
        src_ip_ranges = ["*"]
      }
    }
    rate_limit_options {
      conform_action = "allow"
      exceed_action  = "deny(429)"
      enforce_on_key = "IP"
      rate_limit_threshold {
        count        = 600
        interval_sec = 60
      }
    }
    description = "Coarse per-IP flood protection"
  }

  rule {
    action   = "allow"
    priority = 2147483647
    match {
      versioned_expr = "SRC_IPS_V1"
      config {
        src_ip_ranges = ["*"]
      }
    }
    description = "Default allow; Spring Security protects authenticated routes"
  }
}

resource "google_compute_backend_service" "api" {
  count = local.load_balancer_enabled ? 1 : 0

  project               = var.project_id
  name                  = "${local.resource_prefix}-api"
  protocol              = "HTTP"
  load_balancing_scheme = "EXTERNAL_MANAGED"
  timeout_sec           = 3600
  security_policy       = google_compute_security_policy.api[0].id

  backend {
    group = google_compute_region_network_endpoint_group.api[0].id
  }
}

resource "google_compute_url_map" "api" {
  count = local.load_balancer_enabled ? 1 : 0

  project         = var.project_id
  name            = "${local.resource_prefix}-api"
  default_service = google_compute_backend_service.api[0].id
}

resource "google_compute_managed_ssl_certificate" "api" {
  count = local.load_balancer_enabled ? 1 : 0

  project = var.project_id
  name    = "${local.resource_prefix}-api"

  managed {
    domains = [var.api_domain]
  }
}

resource "google_compute_target_https_proxy" "api" {
  count = local.load_balancer_enabled ? 1 : 0

  project          = var.project_id
  name             = "${local.resource_prefix}-api"
  url_map          = google_compute_url_map.api[0].id
  ssl_certificates = [google_compute_managed_ssl_certificate.api[0].id]
}

resource "google_compute_global_forwarding_rule" "https" {
  count = local.load_balancer_enabled ? 1 : 0

  project               = var.project_id
  name                  = "${local.resource_prefix}-https"
  ip_address            = google_compute_global_address.api[0].id
  port_range            = "443"
  target                = google_compute_target_https_proxy.api[0].id
  load_balancing_scheme = "EXTERNAL_MANAGED"
}

resource "google_compute_url_map" "http_redirect" {
  count = local.load_balancer_enabled ? 1 : 0

  project = var.project_id
  name    = "${local.resource_prefix}-http-redirect"

  default_url_redirect {
    https_redirect         = true
    redirect_response_code = "MOVED_PERMANENTLY_DEFAULT"
    strip_query            = false
  }
}

resource "google_compute_target_http_proxy" "http_redirect" {
  count = local.load_balancer_enabled ? 1 : 0

  project = var.project_id
  name    = "${local.resource_prefix}-http-redirect"
  url_map = google_compute_url_map.http_redirect[0].id
}

resource "google_compute_global_forwarding_rule" "http" {
  count = local.load_balancer_enabled ? 1 : 0

  project               = var.project_id
  name                  = "${local.resource_prefix}-http"
  ip_address            = google_compute_global_address.api[0].id
  port_range            = "80"
  target                = google_compute_target_http_proxy.http_redirect[0].id
  load_balancing_scheme = "EXTERNAL_MANAGED"
}
