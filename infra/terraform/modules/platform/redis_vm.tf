locals {
  redis_vm_enabled = var.redis_backend == "vm" || var.provision_redis_vm
}

# Runs Redis as a TLS-only Docker workload on a private, hardened VM.
resource "google_service_account" "redis" {
  count = local.redis_vm_enabled ? 1 : 0

  project      = var.project_id
  account_id   = "${local.resource_prefix}-redis"
  display_name = "My Pasture ${var.environment} Redis VM"

  depends_on = [google_project_service.required]
}

resource "google_project_iam_member" "redis_logging" {
  count = local.redis_vm_enabled ? 1 : 0

  project = var.project_id
  role    = "roles/logging.logWriter"
  member  = "serviceAccount:${google_service_account.redis[0].email}"
}

resource "google_secret_manager_secret_iam_member" "redis_vm" {
  for_each = local.redis_vm_enabled ? toset([
    "redis-password",
    "redis-ca-certificate",
    "redis-server-certificate",
    "redis-server-key",
  ]) : toset([])

  project   = var.project_id
  secret_id = google_secret_manager_secret.runtime[each.value].secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.redis[0].email}"
}

resource "google_compute_address" "redis" {
  count = local.redis_vm_enabled ? 1 : 0

  project      = var.project_id
  name         = "${local.resource_prefix}-redis"
  region       = var.region
  address_type = "INTERNAL"
  address      = var.redis_internal_ip
  subnetwork   = google_compute_subnetwork.serverless.id
}

resource "google_compute_firewall" "redis" {
  count = local.redis_vm_enabled ? 1 : 0

  project   = var.project_id
  name      = "${local.resource_prefix}-allow-redis"
  network   = google_compute_network.main.name
  direction = "INGRESS"
  priority  = 1000

  source_ranges           = [google_compute_subnetwork.serverless.ip_cidr_range]
  target_service_accounts = [google_service_account.redis[0].email]

  allow {
    protocol = "tcp"
    ports    = ["6379"]
  }

  log_config {
    metadata = "INCLUDE_ALL_METADATA"
  }
}

resource "google_compute_firewall" "redis_iap_ssh" {
  count = local.redis_vm_enabled ? 1 : 0

  project   = var.project_id
  name      = "${local.resource_prefix}-allow-redis-iap-ssh"
  network   = google_compute_network.main.name
  direction = "INGRESS"
  priority  = 1000

  source_ranges           = ["35.235.240.0/20"]
  target_service_accounts = [google_service_account.redis[0].email]

  allow {
    protocol = "tcp"
    ports    = ["22"]
  }

  log_config {
    metadata = "INCLUDE_ALL_METADATA"
  }
}

resource "google_compute_instance" "redis" {
  count = local.redis_vm_enabled ? 1 : 0

  project                   = var.project_id
  name                      = "${local.resource_prefix}-redis"
  zone                      = var.redis_zone
  machine_type              = var.redis_machine_type
  allow_stopping_for_update = true
  deletion_protection       = var.environment == "prod"

  boot_disk {
    auto_delete = true
    initialize_params {
      image = "projects/cos-cloud/global/images/family/cos-stable"
      size  = var.redis_boot_disk_size_gb
      type  = "pd-balanced"
    }
  }

  network_interface {
    subnetwork = google_compute_subnetwork.serverless.id
    network_ip = google_compute_address.redis[0].address
  }

  service_account {
    email  = google_service_account.redis[0].email
    scopes = ["cloud-platform"]
  }

  metadata = {
    enable-oslogin         = "TRUE"
    block-project-ssh-keys = "TRUE"
  }

  metadata_startup_script = <<-EOT
    #!/bin/bash
    set -euo pipefail

    exec > >(tee -a /var/log/redis-startup.log | logger -t redis-startup) 2>&1

    PROJECT_ID="${var.project_id}"
    SECRET_PREFIX="${local.resource_prefix}"
    REDIS_IMAGE="${var.redis_container_image}"
    REDIS_ROOT="/mnt/stateful_partition/redis"

    metadata_token() {
      curl --fail --silent \
        --header "Metadata-Flavor: Google" \
        "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token" \
        | sed -n 's/.*"access_token"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p'
    }

    secret_value() {
      local secret_name="$1"
      local token payload value

      for attempt in $(seq 1 30); do
        token=$(metadata_token)
        payload=$(curl --fail --silent \
          --header "Authorization: Bearer $token" \
          "https://secretmanager.googleapis.com/v1/projects/$PROJECT_ID/secrets/$SECRET_PREFIX-$secret_name/versions/latest:access") || true
        value=$(printf '%s' "$payload" \
          | sed -n 's/.*"data"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' \
          | tr '_-' '/+' \
          | base64 -d 2>/dev/null) || true

        if [ -n "$value" ]; then
          printf '%s' "$value"
          return 0
        fi

        sleep 10
      done

      echo "Secret $secret_name is unavailable" >&2
      return 1
    }

    install -d -m 0700 "$REDIS_ROOT/tls" "$REDIS_ROOT/config"
    install -d -m 0750 "$REDIS_ROOT/data"

    REDIS_PASSWORD=$(secret_value "redis-password")
    secret_value "redis-ca-certificate" > "$REDIS_ROOT/tls/ca.crt"
    secret_value "redis-server-certificate" > "$REDIS_ROOT/tls/server.crt"
    secret_value "redis-server-key" > "$REDIS_ROOT/tls/server.key"

    cat > "$REDIS_ROOT/config/redis.conf" <<EOF
    bind 0.0.0.0
    protected-mode yes
    port 0
    tls-port 6379
    tls-cert-file /tls/server.crt
    tls-key-file /tls/server.key
    tls-ca-cert-file /tls/ca.crt
    tls-auth-clients no
    requirepass $REDIS_PASSWORD
    appendonly yes
    appendfsync everysec
    maxmemory 1gb
    maxmemory-policy allkeys-lru
    rename-command FLUSHALL ""
    rename-command FLUSHDB ""
    rename-command CONFIG ""
    dir /data
    EOF

    chmod 0600 "$REDIS_ROOT/tls/server.key" "$REDIS_ROOT/config/redis.conf"
    chmod 0644 "$REDIS_ROOT/tls/ca.crt" "$REDIS_ROOT/tls/server.crt"
    chown -R 999:999 "$REDIS_ROOT"

    until docker info >/dev/null 2>&1; do
      sleep 2
    done

    docker pull "$REDIS_IMAGE"
    docker rm --force mypasture-redis >/dev/null 2>&1 || true
    docker run --detach \
      --name mypasture-redis \
      --restart unless-stopped \
      --network host \
      --user 999:999 \
      --cap-drop ALL \
      --security-opt no-new-privileges \
      --log-opt max-size=10m \
      --log-opt max-file=3 \
      --mount type=bind,source="$REDIS_ROOT/data",target=/data \
      --mount type=bind,source="$REDIS_ROOT/tls",target=/tls,readonly \
      --mount type=bind,source="$REDIS_ROOT/config/redis.conf",target=/usr/local/etc/redis/redis.conf,readonly \
      "$REDIS_IMAGE" redis-server /usr/local/etc/redis/redis.conf
  EOT

  shielded_instance_config {
    enable_secure_boot          = true
    enable_vtpm                 = true
    enable_integrity_monitoring = true
  }

  labels = {
    application = "mypasture"
    environment = var.environment
    role        = "redis"
  }

  depends_on = [
    google_compute_firewall.redis,
    google_compute_firewall.redis_iap_ssh,
    google_project_iam_member.redis_logging,
    google_secret_manager_secret_iam_member.redis_vm,
  ]
}
