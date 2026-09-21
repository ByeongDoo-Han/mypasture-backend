locals {
  required_secret_env = merge(
    {
      DATABASE_PASSWORD              = "database-password"
      JWT_SECRET                     = "jwt-secret"
      OPENAI_API_KEY                 = "openai-api-key"
      AI_PRIVACY_HASH_SECRET         = "ai-privacy-hash-secret"
      COUPON_CODE_PEPPER             = "coupon-code-pepper"
      EMAIL_VERIFICATION_HMAC_SECRET = "email-verification-hmac-secret"
    },
    local.smtp_enabled ? {
      SMTP_USERNAME = "smtp-username"
      SMTP_PASSWORD = "smtp-password"
    } : {},
    var.redis_auth_enabled ? { REDIS_PASSWORD = "redis-password" } : {},
  )

  # 관리자 코드의 키와 추가 SMTP 자격증명은 API에만 전달합니다.
  api_secret_env = merge(
    local.required_secret_env,
    var.admin_email_login_enabled ? {
      ADMIN_EMAIL_LOGIN_HMAC_SECRET = "admin-email-login-hmac-secret"
      SMTP_USERNAME                 = "smtp-username"
      SMTP_PASSWORD                 = "smtp-password"
    } : {},
  )

  migration_secret_env = merge(local.required_secret_env, {
    DATABASE_PASSWORD = "migration-database-password"
  })

  secret_ids = setunion(
    toset(values(local.required_secret_env)),
    toset(values(local.api_secret_env)),
    toset(values(local.migration_secret_env)),
    toset([
      "redis-ca-certificate",
      "redis-server-certificate",
      "redis-server-key",
      "smtp-username",
      "smtp-password",
      "admin-email-login-hmac-secret",
    ]),
    var.firebase_enabled ? toset(["firebase-service-account"]) : toset([]),
  )
}

resource "google_secret_manager_secret_version" "redis_ca" {
  count = local.memorystore_enabled ? 1 : 0

  secret      = google_secret_manager_secret.runtime["redis-ca-certificate"].id
  secret_data = google_redis_instance.main[0].server_ca_certs[0].cert
}

resource "google_secret_manager_secret" "runtime" {
  for_each = local.secret_ids

  project   = var.project_id
  secret_id = "${local.resource_prefix}-${each.value}"

  replication {
    auto {}
  }

  depends_on = [google_project_service.required]
}

resource "google_secret_manager_secret_iam_member" "runtime" {
  for_each = {
    for id, secret in google_secret_manager_secret.runtime : id => secret
    if !contains(["redis-server-certificate", "redis-server-key"], id)
  }

  project   = var.project_id
  secret_id = each.value.secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.runtime.email}"
}
