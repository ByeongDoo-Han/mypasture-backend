# 관리자 이메일 코드 로그인

## 업무 흐름과 정책

ADMIN 계정에 이메일로 6자리 코드를 보내고 확인 시 기존 JWT/refresh 세션을 발급합니다. 이메일 단일 요소 인증이며 TOTP 또는 다중 요소 인증은 아닙니다. 일반 회원 로그인은 유지합니다.

- `POST /api/v1/auth/admin-email-login/requests`: `{email}` → `{challengeId, expiresInSeconds:300, resendAfterSeconds:60}`.
- `POST /api/v1/auth/admin-email-login/confirm`: `{challengeId,code}` → 기존 `UserLoginResponse`. 인증 실패는 동일한 401 응답입니다.
- 비활성 기능, 부적격 계정, 발송 실패, 쿨다운 및 한도 초과도 요청 응답 형태는 같습니다. 이 응답은 메일 배달을 보장하지 않습니다. SMTP 처리 시간까지 같게 만들지는 않습니다.
- 계정별 5분 만료, 60초 재발송 대기, 첫 실패부터 10분간 최대 5회 실패, 첫 발송부터 1시간 최대 5회 발송입니다. 재발송으로 실패 횟수가 초기화되지 않습니다. 기존 인증 IP rate limit도 적용합니다.
- V42 `admin_email_login_states`에는 challenge/user/purpose에 결합한 HMAC만 저장합니다. 평문 코드는 DB와 로그에 남기지 않습니다.
- 계정 행 잠금으로 재발급/확인/소비를 직렬화하고 확인 시 ADMIN, 활성 계정 및 authVersion을 다시 검사합니다. JWT/refresh 발급 실패 시 코드 소비도 롤백합니다.
- SMTP는 계정 잠금 트랜잭션 안에서 동기로 호출합니다. 연결/읽기/쓰기 제한은 각각 3초, 요청 트랜잭션 제한은 20초입니다. SMTP 수락 후 DB 커밋 실패 시 사용할 수 없는 코드가 배달될 수 있으며 새 코드로 복구합니다. 대량 발송용 설계는 아닙니다.

## 환경변수

| 변수 | 설정 |
| --- | --- |
| `ADMIN_EMAIL_LOGIN_ENABLED` | 기본 `false`; 검증된 SMTP 설정 후 활성화 |
| `ADMIN_EMAIL_LOGIN_HMAC_SECRET` | 독립된 무작위 32바이트 이상 비밀값, Secret Manager 참조 |
| `ADMIN_EMAIL_LOGIN_FROM` | `support@mypasture.app` |
| `SMTP_HOST`, `SMTP_PORT` | SMTP 제공업체 주소/포트 |
| `SMTP_USERNAME`, `SMTP_PASSWORD` | Secret Manager의 인증 정보 |
| `SMTP_STARTTLS_ENABLED` | 기본 `true`; 465 implicit TLS에서는 `false` |
| `SMTP_SSL_ENABLED` | 기본 `false`; 465 implicit TLS에서는 `true` |

운영에서 기능을 켜면 HMAC, 발신 주소, SMTP 자격증명과 암호화 전송을 시작 시 검증합니다. 운영 발신 주소와 비밀값에는 대체 기본값이 없습니다. SMTP 실패를 로그 발송으로 대체하지 않습니다.

### Resend 연결 준비

발송 제공업체는 Resend 무료 플랜으로 선택했습니다. 기존 Cloudflare Email Routing은 수신용으로 유지합니다.

- SMTP: `smtp.resend.com:587`, STARTTLS=true/SSL=false, 사용자명 `resend`.
- 발신 주소: `support@mypasture.app`. Resend에서 `mypasture.app` 발송 도메인을 인증해야 합니다.
- GCP 프로젝트: `my-pasture`. 사용자명은 `mypasture-staging-smtp-username`, API 키는 `mypasture-staging-smtp-password` Secret 버전으로 저장합니다. 키 값은 채팅/소스/명령행 인자/로그에 기록하지 않습니다.
- Cloudflare 발송 인증 레코드: `send` CNAME → `send.forge.rmta.net`, `rsend` CNAME → `rsend-apne1.forge.rmta.net`, `resend._domainkey` TXT → Resend 발급 공개키. CNAME은 DNS only입니다. 기존 루트 MX와 Email Routing은 유지합니다.
- API 키 저장과 발송 도메인 인증이 끝나면 SMTP 인증 및 지정 관리자 대상 실제 수신을 확인합니다. 운영 API/관리자 배포는 별도 승인 후 수행합니다.

[공식 SMTP 설정](https://resend.com/docs/send-with-smtp), [Cloudflare DNS 설정](https://resend.com/docs/knowledge-base/cloudflare).

### 이전 Cloudflare 발송 검토

2026-09-21 공식 SMTP 설정은 `smtp.mx.cloudflare.net:465`, 사용자명 `api_token`, 비밀번호는 Email Sending: Edit 권한의 Cloudflare API token입니다. STARTTLS=false/SSL=true를 사용합니다. Cloudflare 로그인 이메일은 SMTP 사용자명이 아닙니다. [공식 SMTP 문서](https://developers.cloudflare.com/email-service/api/send-emails/smtp/)

해당 계정의 Email Sending 화면은 Workers Paid 가입을 요구했습니다. 사용자가 유료 가입을 보류했으므로 구매, 도메인 발송 등록, 토큰 발급 및 실제 발송을 진행하지 않았습니다. GCP `my-pasture`의 기존 `mypasture-staging-smtp-username`, `mypasture-staging-smtp-password` Secret에는 확인 당시 버전이 없습니다. 값은 소스나 채팅에 넣지 않습니다.

## 계정 프로비저닝과 배포

`scripts/provision-existing-admin.sql`은 운영자가 확인한 이메일과 UUID가 함께 일치하는 활성 기존 계정만 승격합니다. 신규 비밀번호를 만들지 않습니다. USER→ADMIN 변경 시 auth_version 증가 및 refresh 세션 폐기로 재로그인을 요구하며, 이미 ADMIN이면 변경하지 않습니다. 실행 대상 DB와 계정은 실행 전에 별도 확인합니다.

이번 요청의 지정 계정은 공개 운영 API가 연결된 DB에서 활성 기존 계정으로 확인하고 관리자 승격을 완료했습니다. 개인 이메일/UUID는 저장소에 기록하지 않습니다.

공개 `api.mypasture.app`은 GCP `my-pasture`의 이름에 staging이 포함된 API/DB 리소스를 사용합니다. 이름만 보고 비운영으로 판단하면 안 됩니다. 관리자 사이트는 Vercel을 사용합니다. main 병합이 배포를 유발할 수 있으므로 운영 승인 전에 병합하지 않습니다.

활성화 순서:

1. SMTP 발송 기능과 도메인 SPF/DKIM 확인, Secret 버전 생성 및 런타임 서비스 계정의 최소 접근 권한 확인.
2. 운영 승인 후 백엔드 배포와 V42 적용. 기능은 SMTP/HMAC 설정이 준비된 경우에만 활성화.
3. 지정 관리자에게 실제 코드 발송 및 확인. 수신/만료/재발송/권한을 운영에서 검증.
4. 관리자 main PR 별도 승인 후 배포, 브라우저에서 실제 코드 로그인 확인.

문제 발생 시 관리자 배포를 이전 버전으로 되돌리고 백엔드 기능을 비활성화합니다. V42는 추가 테이블이므로 삭제하지 않고 유지하며, 수정이 필요하면 후속 migration을 사용합니다.

## 검증과 인수인계

- 서버 전체 테스트 219건 통과, PostgreSQL 16/pgvector에서 Flyway 전체 migration + 관리자 인증 13건 통과.
- 정상, 만료, 재발송/제한, 잘못된 코드, 권한/인증 버전 변경, SMTP 실패, 동시 확인의 단일 성공, 세션 발급 실패 롤백 검증.
- SMTP 어댑터는 mock으로 TLS 설정과 비밀값 없는 오류 변환을 검증했습니다. 실제 네트워크 발송은 실행하지 않았습니다.
- HTTP 예제: `http/admin-email-login.http`. API interface에 HTTP 계약이 있습니다.
- 관리자 저장소 issue #49가 이메일/코드 화면과 BFF를 담당합니다. 모바일 변경은 필요 없습니다.
- 2026-09-22: GCP SMTP password Secret v1 저장, Resend TLS/SMTP 인증 성공, 도쿄 리전 도메인 Verified 확인. 지정 관리자에게 보낸 연결 확인 메일은 Resend Delivered 확인. 코드의 운영 배포와 실제 OTP 로그인은 별도 승인 후 검증합니다.
- 관리자 전용 HMAC Secret `mypasture-staging-admin-email-login-hmac-secret` v1과 해당 Secret의 runtime service account 읽기 권한을 준비했습니다. 기존 일반 이메일 인증 secret과 분리합니다.


## Terraform 설정과 기존 Secret 인수

공개 운영 API가 사용하는 실제 root는 `infra/terraform/environments/staging`이며 프로젝트는 `my-pasture`입니다. 해당 root의 관리자 이메일 기본값과 example은 `admin_email_login_enabled=true`, `admin_email_login_from=support@mypasture.app`, `smtp_host=smtp.resend.com`, `smtp_port=587`, `smtp_starttls_enabled=true`, `smtp_ssl_enabled=false`입니다. 별도 `environments/prod` root에서는 관리자 이메일 기본값이 `false`로 유지됩니다. 기존 `.tfvars` 값이 default보다 우선하므로 `smtp_host=smtp.example.com` 같은 이전 placeholder는 실제 apply용 변수 파일에서 갱신합니다. 키·비밀번호·HMAC 값은 변수 파일에 넣지 않습니다.

API에만 `ADMIN_EMAIL_LOGIN_ENABLED`, 발신 주소, HMAC 및 필요한 SMTP username/password Secret 참조를 연결합니다. worker/migration/backfill은 관리자 인증이 비활성 상태이며 관리자 HMAC을 받지 않습니다. 일반 회원 이메일 인증/비밀번호 재설정/incident 발송 모드는 그대로 유지합니다. SMTP 자격증명과 HMAC은 Secret Manager의 `latest` 버전을 참조합니다.

이미 생성한 Secret과 runtime IAM은 생성하려고 apply하기 전에 **올바른 remote state에 import**합니다. 기존 managed SMTP secret/IAM은 state에 이미 있으면 다시 import하지 않습니다. 아래 `approved-staging.tfvars`는 검토한 실제 이미지·리소스 설정이 담긴 로컬 파일 경로로 바꿉니다.

```bash
terraform -chdir=infra/terraform/environments/staging init -reconfigure \
  -backend-config='bucket=my-pasture-terraform-state' \
  -backend-config='prefix=mypasture/staging/platform'

terraform -chdir=infra/terraform/environments/staging import \
  -var-file=/absolute/path/approved-staging.tfvars \
  'module.platform.google_secret_manager_secret.runtime["admin-email-login-hmac-secret"]' \
  'projects/my-pasture/secrets/mypasture-staging-admin-email-login-hmac-secret'

terraform -chdir=infra/terraform/environments/staging import \
  -var-file=/absolute/path/approved-staging.tfvars \
  'module.platform.google_secret_manager_secret_iam_member.runtime["admin-email-login-hmac-secret"]' \
  'projects/my-pasture/secrets/mypasture-staging-admin-email-login-hmac-secret roles/secretmanager.secretAccessor serviceAccount:mypasture-staging-runtime@my-pasture.iam.gserviceaccount.com'
```

IAM import 형식은 [사용 중인 Google provider 7.45.0 문서](https://github.com/hashicorp/terraform-provider-google/blob/v7.45.0/website/docs/r/secret_manager_secret_iam.html.markdown#import)를 따릅니다. Secret payload/version은 이 Terraform 변경에서 생성하거나 import하지 않습니다.

백엔드 코드/V42 배포 후 API와 필요한 Secret/IAM만 대상으로 plan을 저장하고 읽어 본 뒤 적용합니다. target도 의존 리소스를 포함하므로 다른 리소스 변경·삭제가 섞였는지 확인해야 합니다. 이 변경을 이유로 전체 인프라 apply를 실행하지 않습니다.

```bash
terraform -chdir=infra/terraform/environments/staging plan \
  -var-file=/absolute/path/approved-staging.tfvars \
  -target='module.platform.google_secret_manager_secret.runtime["admin-email-login-hmac-secret"]' \
  -target='module.platform.google_secret_manager_secret_iam_member.runtime["admin-email-login-hmac-secret"]' \
  -target='module.platform.google_cloud_run_v2_service.api[0]' \
  -out=admin-email-login.tfplan

terraform -chdir=infra/terraform/environments/staging show admin-email-login.tfplan
terraform -chdir=infra/terraform/environments/staging apply admin-email-login.tfplan
```

관리자 기능을 Terraform에서 `false`로 바꾸면 API의 관리자 HMAC 및 추가 SMTP 참조가 제거됩니다. HMAC Secret 메타데이터와 IAM은 기존 SMTP Secret과 마찬가지로 그대로 보존하여 기능 중단이 키 폐기를 유발하지 않습니다. 다른 환경에서도 빈 Secret 메타데이터만 생성하며 값/version은 생성하지 않습니다. 현재 API/worker/job은 runtime service account를 공유하므로 API에만 env를 연결하더라도 IAM 주체 수준의 격리는 아닙니다.

이 절차의 `fmt`/`validate` 성공은 실제 import/apply 또는 새 리비전의 관리자 로그인 성공을 뜻하지 않습니다. 적용 후 공개 API readiness, 실제 이메일 OTP 로그인, 이전 로그인 방식의 영향, 마지막 targeted plan을 각각 확인합니다.
