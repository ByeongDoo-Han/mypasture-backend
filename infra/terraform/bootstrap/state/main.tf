resource "google_storage_bucket" "terraform_state" {
  project                     = var.project_id
  name                        = var.bucket_name
  location                    = var.region
  storage_class               = "STANDARD"
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
  force_destroy               = false

  versioning {
    enabled = true
  }

  lifecycle_rule {
    condition {
      age                = 365
      with_state         = "ARCHIVED"
      num_newer_versions = 20
    }
    action {
      type = "Delete"
    }
  }
}

output "bucket_name" {
  description = "Use this value as the GCS backend bucket."
  value       = google_storage_bucket.terraform_state.name
}
