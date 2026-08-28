# C4 Deployment View

GCP staging/prod의 기준 배포 구조와 GitHub Actions 전달 경로입니다.

```mermaid
flowchart TB
    Developer["Developer"] --> GitHub["GitHub main + Server CI"]
    GitHub -->|OIDC, main ref only| WIF["GCP Workload Identity Federation"]
    WIF --> DeploySA["Environment Deploy Service Account"]
    GitHub -->|linux/amd64 digest image| Registry["Artifact Registry"]

    subgraph GCP["GCP Project / Region"]
        VPC["VPC + Serverless network"]
        Migration["Cloud Run Migration Job"]
        Worker["Cloud Run Worker Service"]
        API["Cloud Run API Service"]
        Backfill["Cloud Run Embedding Job"]
        SQL[("Private Cloud SQL PostgreSQL")]
        Redis[("Redis VM / Memorystore option")]
        Secrets["Secret Manager"]

        Migration --> SQL
        Worker --> SQL
        Worker --> Redis
        API --> SQL
        API --> Redis
        Backfill --> SQL
        Secrets -.-> Migration
        Secrets -.-> Worker
        Secrets -.-> API
        Secrets -.-> Backfill
        VPC --- Migration
        VPC --- Worker
        VPC --- API
        VPC --- Backfill
    end

    DeploySA --> Migration
    DeploySA --> Worker
    DeploySA --> API
    DeploySA --> Backfill
    Registry --> Migration
    Registry --> Worker
    Registry --> API
    Registry --> Backfill
```

## 자동배포 순서

```mermaid
sequenceDiagram
    participant CI as Server CI
    participant CD as Deploy Workflow
    participant AR as Artifact Registry
    participant MJ as Migration Job
    participant W as Worker
    participant API as API
    participant Smoke as Smoke Tests

    CI-->>CD: main push success
    CD->>CD: GitHub OIDC 인증
    CD->>AR: commit SHA image push
    CD->>MJ: digest image update + execute
    MJ-->>CD: success
    CD->>W: digest image update
    CD->>API: digest image update
    CD->>Smoke: readiness + version + 66 books
    alt smoke failure
        CD->>W: previous image restore
        CD->>API: previous image restore
        CD->>MJ: previous image metadata restore
    end
```

Staging은 `main` CI 성공 후 자동배포합니다. Production은 `main`에서 수동 실행하고 GitHub Environment 승인 정책을 적용합니다. 이미지 rollback은 DB schema를 되돌리지 않으므로 migration은 expand/contract 방식이어야 합니다.

[GCP Terraform 상세 가이드](../../gcp-terraform-deployment.ko.md)
[아키텍처 인덱스로 돌아가기](../README.md)
