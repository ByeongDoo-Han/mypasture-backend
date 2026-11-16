# Admin Cloud Run 인프라 운영

## 책임 경계

`mypasture-<environment>-admin`은 Next.js 화면과 BFF만 실행합니다. 관리자 데이터 처리는 HTTPS Spring API를 통해서만 수행하며 Admin runtime service account에는 Cloud SQL, Redis, Secret Manager 접근 권한을 부여하지 않습니다.

Cloud Run direct IAP가 운영자 Google 계정을 1차로 제한하고, 백엔드 `ADMIN` 역할 검사가 2차 권한 검사를 담당합니다. Admin 서비스에는 `allUsers` Cloud Run Invoker를 부여하지 않습니다.

## Terraform 입력

```hcl
admin_enabled         = true
admin_container_image = "asia-northeast3-docker.pkg.dev/PROJECT/mypasture-ENV-admin/admin@sha256:DIGEST"
admin_api_base_url     = "https://api.mypasture.app"
admin_iap_members      = [
  "user:operator@example.com",
]
```

개인 계정이 늘어나면 개별 `user:` 목록보다 Google Group 하나를 `group:`으로 등록하는 편이 입사·퇴사와 권한 회수에 유리합니다. `admin_iap_members`가 비어 있으면 서비스는 생성되지만 일반 운영자는 접근할 수 없습니다.

## 최초 적용

Admin Artifact Registry와 runtime service account를 먼저 생성해야 초기 이미지를 push할 수 있습니다.

1. `admin_enabled = false`로 platform Terraform을 적용합니다.
2. `admin_artifact_registry_repository`, `admin_runtime_service_account_email` output을 확인합니다.
3. `bootstrap/github-actions`에 Admin 저장소 정보를 추가하고 WIF를 적용합니다.
4. Admin 배포 workflow로 초기 image를 push합니다.
5. digest URI와 HTTPS API 주소, IAP member를 설정하고 `admin_enabled = true`로 plan/apply합니다.

프로젝트에서 direct IAP를 처음 활성화할 때 OAuth 설정이 없으면 Cloud Console에서 Admin Cloud Run의 Security 설정을 열어 IAP 초기 설정을 한 번 완료합니다. Terraform 재적용 후 `Iap Enabled: true`를 확인합니다.

## GitHub OIDC 설정

Admin repository numeric ID를 확인합니다.

```bash
gh api repos/ByeongDoo-Han/mypasture-admin --jq '.id'
```

`bootstrap/github-actions/terraform.tfvars`에 `admin_github_repository`, `admin_github_repository_id`, `admin_deployments`를 채웁니다. Admin provider는 immutable repository ID, owner ID와 `refs/heads/main`을 모두 검사합니다.

```bash
terraform plan -out=github-actions.tfplan
terraform apply github-actions.tfplan
terraform output admin_github_environment_variables
```

출력값은 `mypasture-admin` 저장소의 동일한 GitHub Environment에 등록합니다. 최초 output의 `GCP_DEPLOY_ENABLED`는 `false`이며, image-only push와 Cloud Run/IAP 검증이 끝난 뒤 staging만 `true`로 바꿉니다. Admin deploy service account 권한은 해당 Admin Artifact Registry writer, 조건부 Cloud Run developer, Admin runtime service account impersonation으로 제한됩니다. Cloud Run 권한의 IAM condition은 같은 환경의 `mypasture-<environment>-admin` 서비스만 변경하도록 제한합니다.

## 적용 전 체크리스트

- [ ] `admin_container_image`가 tag가 아닌 검증된 digest인지 확인
- [ ] `admin_api_base_url`이 HTTPS 운영 주소인지 확인
- [ ] staging에서 IAP 로그인과 백엔드 ADMIN 권한을 모두 검증
- [ ] Terraform plan에 API, Cloud SQL, Redis 교체가 없는지 확인
- [ ] production GitHub Environment required reviewer 확인
- [ ] Admin runtime service account에 DB·Redis·Secret 권한이 없는지 확인
- [ ] 비인가 계정과 비ADMIN 계정의 접근 거부 확인

기존 인프라에 unrelated drift가 있으면 Admin 변경과 함께 적용하지 않습니다. Admin 리소스만 별도 승인해 적용하더라도 최종적으로 전체 plan이 의도한 상태인지 다시 확인합니다.
