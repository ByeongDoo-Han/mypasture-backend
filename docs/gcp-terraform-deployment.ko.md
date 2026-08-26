# GCP Terraform 배포 가이드

## 1. 현재 배포 모델

My Pasture 서버는 같은 불변 Docker 이미지를 세 가지 실행 단위에서 사용합니다.

| 실행 단위 | 역할 | 확장 정책 |
|---|---|---|
| Cloud Run API | REST, AI streaming, QT SSE | 요청량에 따라 자동 확장 |
| Cloud Run worker | 알림, 이메일 outbox, 오늘의 말씀, 정리 작업 | 최소/최대 1개 |
| Cloud Run migration Job | Flyway와 JPA schema 검증 후 종료 | 배포마다 먼저 1회 실행 |

운영 데이터는 Cloud SQL PostgreSQL 17과 pgvector에 저장합니다. Redis 캐시, rate limit, Pub/Sub은 외부 IP가 없는 Compute Engine VM의 TLS Docker Redis를 사용합니다. 운영 API는 외부 HTTPS Load Balancer와 Cloud Armor를 통과하고 Cloud Run 기본 URL 직접 접근은 차단합니다.

## 2. 배포 전 준비

환경마다 GCP project를 분리합니다.

- `mypasture-staging`: 기능 및 실제 외부 연동 검증
- `mypasture-prod`: 운영 데이터와 사용자 트래픽
- 선택 사항 `mypasture-infra`: Terraform state와 배포 identity 관리

로컬 최초 bootstrap에는 Google Cloud CLI, Terraform 1.15 이상, Docker가 필요합니다.

```bash
gcloud auth login
gcloud auth application-default login
```

Owner 계정을 일상적인 배포에 사용하지 않습니다. 최초 IAM 구성 후 Terraform 관리자와 애플리케이션 배포자 역할을 분리합니다.

## 3. Remote state 생성

GCS bucket 이름은 전 세계에서 유일해야 합니다.

```bash
cd infra/terraform/bootstrap/state
cp terraform.tfvars.example terraform.tfvars
terraform init
terraform plan
terraform apply
```

생성되는 bucket은 public access prevention, uniform access, object versioning이 적용됩니다. bootstrap의 작은 local state도 민감한 운영 자료로 취급하고 안전한 저장소로 옮긴 뒤 로컬에서 제거합니다.

환경별 backend를 초기화합니다.

```bash
cd infra/terraform/environments/staging
terraform init \
  -backend-config="bucket=YOUR_STATE_BUCKET" \
  -backend-config="prefix=mypasture/staging/platform"
```

운영은 prefix를 `mypasture/prod/platform`으로 바꿉니다. staging과 prod가 같은 state나 Terraform CLI workspace를 공유하지 않게 합니다.

## 4. 기반 리소스 우선 생성

`terraform.tfvars.example`을 `terraform.tfvars`로 복사하고 실제 프로젝트 식별자와 공개 설정만 채웁니다. 비밀번호, OpenAI key, Firebase JSON은 넣지 않습니다.

`monthly_budget_amount`와 `budget_currency_code`는 결제 계정의 통화와 일치해야 합니다. 한국 원화 결제 계정 예시는 `150000`, `KRW`이며 50%, 80%, 100% 도달 시 결제 계정 기본 수신자에게 알림을 보냅니다. 예산은 지출을 자동 차단하지 않으므로 별도의 할당량과 서비스 한도도 함께 운영합니다.

첫 apply에서는 다음 값을 유지합니다.

```hcl
deploy_runtime = false
```

```bash
terraform fmt -check -recursive ../..
terraform validate
terraform plan -out=bootstrap.tfplan
terraform apply bootstrap.tfplan
```

이 단계는 VPC, Cloud SQL, Redis VM, Artifact Registry, secret container, runtime service account를 생성하지만 아직 Cloud Run을 만들지 않습니다.

## 5. DB 계정과 extension 준비

DB 계정 비밀번호는 Terraform이 관리하지 않습니다. Cloud SQL Auth Proxy와 `psql`로 관리자 연결을 만든 뒤 interactive `\password`를 사용합니다. 명령 인자나 shell history에 비밀번호를 남기지 않습니다.

```sql
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE ROLE mypasture_migrator LOGIN;
CREATE ROLE mypasture_app LOGIN;
\password mypasture_migrator
\password mypasture_app

ALTER DATABASE mypasture OWNER TO mypasture_migrator;
\connect mypasture
ALTER SCHEMA public OWNER TO mypasture_migrator;

GRANT CONNECT ON DATABASE mypasture TO mypasture_app;
GRANT USAGE ON SCHEMA public TO mypasture_app;
ALTER DEFAULT PRIVILEGES FOR ROLE mypasture_migrator IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO mypasture_app;
ALTER DEFAULT PRIVILEGES FOR ROLE mypasture_migrator IN SCHEMA public
  GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO mypasture_app;
ALTER DEFAULT PRIVILEGES FOR ROLE mypasture_migrator IN SCHEMA public
  GRANT EXECUTE ON FUNCTIONS TO mypasture_app;
```

extension은 관리자 계정으로 미리 생성합니다. migration 계정에 `cloudsqlsuperuser`를 상시 부여하지 않기 위한 조치입니다.

## 6. Secret payload 등록

Terraform output의 `secret_ids` 목록을 기준으로 값을 등록합니다. 다음 방식은 값을 터미널 인자에 남기지 않고 표준 입력으로 받습니다.

```bash
gcloud secrets versions add SECRET_ID --data-file=-
```

입력 후 `Ctrl+D`로 종료합니다. 필수 secret은 application/migration DB password, Redis password, JWT secret, OpenAI API key, AI privacy hash secret, SMTP 계정, email verification HMAC secret입니다. FCM을 켠 경우 Firebase service account JSON도 필요합니다.

실제 SMTP가 준비되지 않은 staging은 이메일 인증과 비밀번호 재설정을 `log`, 오늘의 말씀 장애 메일을 `disabled`로 배포합니다. 이 경우 SMTP username/password version은 필요하지 않으며, production은 기본값인 `smtp`와 검증된 발신자 및 실제 Secret version을 사용해야 합니다. 로그 전달은 인증 코드를 애플리케이션 로그에 남기므로 staging 접근 권한도 운영자에게만 제한합니다.

Redis VM에는 별도 CA, 서버 인증서, 서버 개인키가 필요합니다. 서버 인증서 SAN에는 Terraform의 `redis_internal_ip`와 VM 이름을 포함합니다. 비밀번호와 개인키는 명령 인자, Terraform 변수, Terraform state에 넣지 않고 Secret Manager version으로 등록합니다.

전환 시에는 먼저 다음처럼 두 Redis를 병행 운영합니다.

```hcl
redis_backend      = "vm"
provision_redis_vm = true
retain_memorystore = true
```

IAP를 통한 VM 내부 검증에서 Docker 컨테이너 실행, TLS 인증서 검증, AUTH `PING`이 성공하고 애플리케이션 E2E를 통과한 후에만 별도 승인으로 `retain_memorystore = false`를 적용합니다.

VM 방식은 관리형 서비스보다 저렴할 수 있지만 OS/Redis 보안 업데이트, 디스크 스냅샷, 인증서 교체, 장애 복구가 운영자 책임입니다. 현재 구성은 단일 노드이고 AOF가 VM 부팅 디스크에 있으므로 VM 재부팅에는 유지되지만 VM 재생성에는 보존되지 않습니다. Redis를 영속 데이터의 원본으로 사용하지 말고, 운영 전 별도 데이터 디스크 또는 스냅샷 복구 절차를 검증합니다.

Firebase JSON은 파일로 등록한 후 원본 파일의 로컬 보관 정책을 확인합니다.

```bash
gcloud secrets versions add SECRET_ID --data-file=/secure/path/firebase-service-account.json
```

## 7. 최초 이미지와 runtime 생성

Artifact Registry 이름은 Terraform output에서 확인합니다. 이미지는 `latest` 대신 commit SHA로 고정합니다.

```bash
gcloud auth configure-docker asia-northeast3-docker.pkg.dev
IMAGE="ARTIFACT_REPOSITORY/api:GIT_COMMIT_SHA"
docker build --tag "${IMAGE}" .
docker push "${IMAGE}"
```

`container_image`을 이 값으로 바꾸고 첫 단계에서는 migration Job만 생성합니다. API와 worker는 아직 생성하지 않습니다.

```hcl
deploy_runtime  = true
deploy_services = false
```

```bash
terraform plan -out=runtime.tfplan
terraform apply runtime.tfplan
```

운영 환경은 output의 `load_balancer_ip`를 `api.mypasture.app` A record로 연결합니다. DNS 전파 후 Google managed certificate가 `ACTIVE`가 되었는지 확인해야 합니다.

## 8. 최초 migration과 검증

```bash
gcloud run jobs execute mypasture-staging-migration \
  --region=asia-northeast3 --wait
```

마이그레이션 성공 후에만 API와 worker를 생성합니다. API와 worker에서는 Flyway가 비활성화되고 migration Job에서만 실행됩니다.

```hcl
deploy_runtime  = true
deploy_services = true
```

```bash
terraform plan -out=services.tfplan
terraform apply services.tfplan

gcloud run services describe mypasture-staging-worker --region=asia-northeast3
gcloud run services describe mypasture-staging-api --region=asia-northeast3
curl --fail https://STAGING_API/actuator/health/readiness
```

로그인, JWT 갱신, 이메일, FCM 실제 기기, AI RAG streaming, QT 참여와 SSE를 순서대로 검증합니다.

## 9. GitHub Actions 배포

Google service account JSON key는 저장하지 않습니다. GitHub OIDC를 신뢰하는 Workload Identity Federation provider와 GitHub Environment 승인 정책을 사용합니다.

배포 service account의 기본 권한은 다음 범위로 제한합니다.

- `roles/artifactregistry.writer`
- `roles/run.admin`
- runtime service account에 대한 `roles/iam.serviceAccountUser`

GitHub의 `staging`, `prod` Environment에 다음 variables를 등록합니다.

| 변수 | 의미 |
|---|---|
| `GCP_PROJECT_ID` | 환경별 project ID |
| `GCP_REGION` | `asia-northeast3` |
| `GCP_ARTIFACT_REPOSITORY` | Terraform이 만든 repository ID |
| `GCP_WORKLOAD_IDENTITY_PROVIDER` | WIF provider 전체 resource name |
| `GCP_DEPLOY_SERVICE_ACCOUNT` | 배포 service account email |
| `API_HEALTH_URL` | 배포 후 검사할 HTTPS API 주소 |

`Deploy Server to GCP` workflow는 이미지를 push하고 migration Job 성공을 확인한 뒤 worker, API 순으로 배포합니다. `prod` GitHub Environment에는 required reviewer를 설정합니다.

## 10. 롤백과 DB 변경 정책

- 애플리케이션 장애: 직전 정상 Cloud Run revision으로 traffic을 복원합니다.
- migration 실패: worker와 API를 갱신하지 않고 배포를 중단합니다.
- DB migration: 구버전과 신버전이 함께 동작하는 확장 변경을 먼저 배포하고 제거는 다음 release에서 수행합니다.
- 파괴적 SQL과 데이터 대량 변환은 일반 Flyway release와 분리합니다.
- 운영 apply 전 Cloud SQL backup과 PITR 상태를 확인하고 정기적으로 복구 훈련을 수행합니다.

## 11. 운영 승인 체크리스트

- [ ] staging Terraform plan/apply 및 E2E 통과
- [ ] Artifact Registry 취약점 검사 결과 검토
- [ ] secret version과 runtime IAM 최소 권한 확인
- [ ] Cloud SQL HA, backup, PITR, deletion protection 확인
- [ ] Redis VM 외부 IP 없음, TLS/AUTH, private network와 IAP 접근만 허용 확인
- [ ] Redis AOF, 디스크 스냅샷, 인증서 교체와 VM 재생성 복구 훈련 확인
- [ ] 관리형 인증서 `ACTIVE`, `run.app` 직접 접근 차단 확인
- [ ] Cloud Armor와 애플리케이션 rate limit 동시 검증
- [ ] 5xx 알림, 예산 알림, 로그 민감정보 마스킹 확인
- [ ] 실제 기기 FCM/APNs와 SMTP 전달 확인
- [ ] AI 비용 제한, timeout, streaming 재연결 확인
- [ ] 부하 테스트에서 DB connection과 Cloud Run max instance 한도 확인
- [ ] rollback 및 DB restore 훈련 완료
