locals {
  common_environment = merge(
    {
      SPRING_PROFILES_ACTIVE             = "prod,gcp"
      DATABASE_URL                       = "jdbc:postgresql://${google_sql_database_instance.main.private_ip_address}:5432/${var.database_name}?sslmode=require"
      DATABASE_USERNAME                  = var.database_username
      DB_POOL_MAX_SIZE                   = "10"
      DB_POOL_MIN_IDLE                   = "2"
      REDIS_HOST                         = google_redis_instance.main.host
      REDIS_PORT                         = tostring(google_redis_instance.main.port)
      REDIS_SSL_ENABLED                  = "true"
      REDIS_CA_CERT_PATH                 = "file:/var/run/secrets/redis/server-ca.pem"
      APP_CORS_ALLOWED_ORIGINS           = var.cors_allowed_origins
      KOR1910_HTML_PATH                  = "/tmp/bible-import-disabled"
      BIBLE_IMPORT_ENABLED               = "false"
      EMAIL_VERIFICATION_DELIVERY        = "smtp"
      EMAIL_VERIFICATION_FROM            = var.mail_from
      PASSWORD_RESET_DELIVERY            = "smtp"
      PASSWORD_RESET_URL                 = var.password_reset_url
      PASSWORD_RESET_FROM                = var.mail_from
      SMTP_HOST                          = var.smtp_host
      SMTP_PORT                          = tostring(var.smtp_port)
      QUIET_TIME_INVITE_BASE_URL         = var.quiet_time_invite_base_url
      QUIET_TIME_REALTIME_REDIS_ENABLED  = "true"
      RATE_LIMIT_ENABLED                 = "true"
      RATE_LIMIT_FAIL_CLOSED             = "true"
      RATE_LIMIT_TRUST_FORWARDED_HEADERS = "true"
      JWT_ISSUER                         = "mypasture"
      AI_RAG_LEXICAL_FALLBACK_ENABLED    = "false"
      GOOGLE_OAUTH_WEB_CLIENT_IDS        = var.google_oauth_web_client_ids
      GOOGLE_OAUTH_IOS_CLIENT_IDS        = var.google_oauth_ios_client_ids
      GOOGLE_OAUTH_ANDROID_CLIENT_IDS    = var.google_oauth_android_client_ids
      APPLE_OAUTH_CLIENT_IDS             = var.apple_oauth_client_ids
      KAKAO_APP_IDS                      = var.kakao_app_ids
    },
    var.redis_auth_enabled ? {} : { REDIS_PASSWORD = "" },
  )

  api_environment = merge(local.common_environment, {
    APP_WORKER_ENABLED                    = "false"
    DAILY_WORD_GENERATION_ENABLED         = "false"
    DAILY_WORD_INCIDENT_DETECTION_ENABLED = "false"
    DAILY_WORD_INCIDENT_EMAIL_DELIVERY    = "disabled"
    AI_EMBEDDING_INDEXING_ENABLED         = "false"
    AI_COMMENTARY_GENERATION_ENABLED      = "false"
    FIREBASE_ENABLED                      = "false"
  })

  worker_environment = merge(local.common_environment, {
    APP_WORKER_ENABLED                    = "true"
    DAILY_WORD_GENERATION_ENABLED         = "true"
    DAILY_WORD_PROVIDER                   = "openai"
    DAILY_WORD_INCIDENT_DETECTION_ENABLED = "true"
    DAILY_WORD_INCIDENT_EMAIL_DELIVERY    = "smtp"
    DAILY_WORD_INCIDENT_EMAIL_FROM        = var.mail_from
    AI_EMBEDDING_INDEXING_ENABLED         = tostring(var.worker_enable_ai_jobs)
    AI_COMMENTARY_GENERATION_ENABLED      = tostring(var.worker_enable_ai_jobs)
    FIREBASE_ENABLED                      = tostring(var.firebase_enabled)
    FIREBASE_PROJECT_ID                   = var.firebase_project_id
    FIREBASE_CREDENTIALS_PATH             = "/var/run/secrets/firebase/firebase-service-account.json"
  })

  migration_environment = merge(local.common_environment, {
    APP_MIGRATION_ONLY                    = "true"
    DATABASE_USERNAME                     = var.migration_database_username
    APP_WORKER_ENABLED                    = "false"
    DAILY_WORD_GENERATION_ENABLED         = "false"
    DAILY_WORD_INCIDENT_DETECTION_ENABLED = "false"
    DAILY_WORD_INCIDENT_EMAIL_DELIVERY    = "disabled"
    AI_EMBEDDING_INDEXING_ENABLED         = "false"
    AI_COMMENTARY_GENERATION_ENABLED      = "false"
    FIREBASE_ENABLED                      = "false"
  })
}

resource "google_cloud_run_v2_service" "api" {
  count = var.deploy_runtime ? 1 : 0

  project             = var.project_id
  name                = "${local.resource_prefix}-api"
  location            = var.region
  ingress             = var.enable_external_load_balancer ? "INGRESS_TRAFFIC_INTERNAL_LOAD_BALANCER" : "INGRESS_TRAFFIC_ALL"
  deletion_protection = var.environment == "prod"

  template {
    service_account                  = google_service_account.runtime.email
    timeout                          = "90s"
    max_instance_request_concurrency = 30

    scaling {
      min_instance_count = var.api_min_instances
      max_instance_count = var.api_max_instances
    }

    vpc_access {
      egress = "PRIVATE_RANGES_ONLY"
      network_interfaces {
        network    = google_compute_network.main.id
        subnetwork = google_compute_subnetwork.serverless.id
      }
    }

    containers {
      image = var.container_image

      ports {
        container_port = 8080
      }

      resources {
        limits = {
          cpu    = "2"
          memory = "2Gi"
        }
        cpu_idle          = true
        startup_cpu_boost = true
      }

      dynamic "env" {
        for_each = local.api_environment
        content {
          name  = env.key
          value = env.value
        }
      }

      volume_mounts {
        name       = "redis-ca-certificate"
        mount_path = "/var/run/secrets/redis"
      }

      dynamic "env" {
        for_each = local.required_secret_env
        content {
          name = env.key
          value_source {
            secret_key_ref {
              secret  = google_secret_manager_secret.runtime[env.value].secret_id
              version = "latest"
            }
          }
        }
      }

      startup_probe {
        initial_delay_seconds = 5
        timeout_seconds       = 3
        period_seconds        = 5
        failure_threshold     = 24
        http_get {
          path = "/actuator/health/liveness"
          port = 8080
        }
      }

      liveness_probe {
        initial_delay_seconds = 10
        timeout_seconds       = 3
        period_seconds        = 10
        failure_threshold     = 3
        http_get {
          path = "/actuator/health/liveness"
          port = 8080
        }
      }
    }


    volumes {
      name = "redis-ca-certificate"
      secret {
        secret = google_secret_manager_secret.runtime["redis-ca-certificate"].secret_id
        items {
          version = google_secret_manager_secret_version.redis_ca.version
          path    = "server-ca.pem"
        }
      }
    }
  }

  depends_on = [
    google_project_service.required,
    google_secret_manager_secret_iam_member.runtime,
  ]

  lifecycle {
    ignore_changes = [template[0].containers[0].image]
  }
}

resource "google_cloud_run_v2_service_iam_member" "public_api" {
  count = var.deploy_runtime ? 1 : 0

  project  = var.project_id
  location = var.region
  name     = google_cloud_run_v2_service.api[0].name
  role     = "roles/run.invoker"
  member   = "allUsers"
}

resource "google_cloud_run_v2_service" "worker" {
  count = var.deploy_runtime && var.worker_enabled ? 1 : 0

  project             = var.project_id
  name                = "${local.resource_prefix}-worker"
  location            = var.region
  ingress             = "INGRESS_TRAFFIC_INTERNAL_ONLY"
  deletion_protection = var.environment == "prod"

  template {
    service_account                  = google_service_account.runtime.email
    timeout                          = "300s"
    max_instance_request_concurrency = 1

    scaling {
      min_instance_count = 1
      max_instance_count = 1
    }

    vpc_access {
      egress = "PRIVATE_RANGES_ONLY"
      network_interfaces {
        network    = google_compute_network.main.id
        subnetwork = google_compute_subnetwork.serverless.id
      }
    }

    containers {
      image = var.container_image

      resources {
        limits = {
          cpu    = "1"
          memory = "1Gi"
        }
        cpu_idle          = false
        startup_cpu_boost = true
      }

      dynamic "env" {
        for_each = local.worker_environment
        content {
          name  = env.key
          value = env.value
        }
      }

      dynamic "env" {
        for_each = local.required_secret_env
        content {
          name = env.key
          value_source {
            secret_key_ref {
              secret  = google_secret_manager_secret.runtime[env.value].secret_id
              version = "latest"
            }
          }
        }
      }

      dynamic "volume_mounts" {
        for_each = var.firebase_enabled ? [1] : []
        content {
          name       = "firebase-service-account"
          mount_path = "/var/run/secrets/firebase"
        }
      }

      volume_mounts {
        name       = "redis-ca-certificate"
        mount_path = "/var/run/secrets/redis"
      }

      startup_probe {
        initial_delay_seconds = 5
        timeout_seconds       = 3
        period_seconds        = 5
        failure_threshold     = 24
        http_get {
          path = "/actuator/health/liveness"
          port = 8080
        }
      }

      liveness_probe {
        initial_delay_seconds = 10
        timeout_seconds       = 3
        period_seconds        = 10
        failure_threshold     = 3
        http_get {
          path = "/actuator/health/liveness"
          port = 8080
        }
      }
    }


    volumes {
      name = "redis-ca-certificate"
      secret {
        secret = google_secret_manager_secret.runtime["redis-ca-certificate"].secret_id
        items {
          version = google_secret_manager_secret_version.redis_ca.version
          path    = "server-ca.pem"
        }
      }
    }

    dynamic "volumes" {
      for_each = var.firebase_enabled ? [1] : []
      content {
        name = "firebase-service-account"
        secret {
          secret = google_secret_manager_secret.runtime["firebase-service-account"].secret_id
          items {
            version = "latest"
            path    = "firebase-service-account.json"
          }
        }
      }
    }
  }

  depends_on = [
    google_project_service.required,
    google_secret_manager_secret_iam_member.runtime,
  ]

  lifecycle {
    ignore_changes = [template[0].containers[0].image]
  }
}

resource "google_cloud_run_v2_job" "migration" {
  count = var.deploy_runtime ? 1 : 0

  project             = var.project_id
  name                = "${local.resource_prefix}-migration"
  location            = var.region
  deletion_protection = var.environment == "prod"

  template {
    template {
      service_account = google_service_account.runtime.email
      max_retries     = 1
      timeout         = "900s"

      vpc_access {
        egress = "PRIVATE_RANGES_ONLY"
        network_interfaces {
          network    = google_compute_network.main.id
          subnetwork = google_compute_subnetwork.serverless.id
        }
      }

      containers {
        image = var.container_image

        resources {
          limits = {
            cpu    = "1"
            memory = "1Gi"
          }
        }

        dynamic "env" {
          for_each = local.migration_environment
          content {
            name  = env.key
            value = env.value
          }
        }

        dynamic "env" {
          for_each = local.migration_secret_env
          content {
            name = env.key
            value_source {
              secret_key_ref {
                secret  = google_secret_manager_secret.runtime[env.value].secret_id
                version = "latest"
              }
            }
          }
        }

        volume_mounts {
          name       = "redis-ca-certificate"
          mount_path = "/var/run/secrets/redis"
        }
      }


      volumes {
        name = "redis-ca-certificate"
        secret {
          secret = google_secret_manager_secret.runtime["redis-ca-certificate"].secret_id
          items {
            version = google_secret_manager_secret_version.redis_ca.version
            path    = "server-ca.pem"
          }
        }
      }
    }
  }

  depends_on = [
    google_project_service.required,
    google_secret_manager_secret_iam_member.runtime,
  ]

  lifecycle {
    ignore_changes = [template[0].template[0].containers[0].image]
  }
}
