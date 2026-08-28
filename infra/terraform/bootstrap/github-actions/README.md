# GitHub Actions OIDC Bootstrap

이 Terraform root는 My Pasture backend GitHub Actions가 서비스 계정 JSON key 없이 GCP에 배포할 수 있도록
Workload Identity Federation과 환경별 배포 계정을 구성합니다. 애플리케이션 플랫폼 state와 분리되어 있어
Cloud Run 배포 중 OIDC 신뢰 기반이 함께 변경되지 않습니다.

## 보안 경계

- GitHub repository 이름뿐 아니라 변경되지 않는 repository ID와 owner ID를 검증합니다.
- `refs/heads/main`에서 발급된 토큰만 허용합니다.
- staging과 prod는 서로 다른 deploy service account를 사용합니다.
- deploy account는 Artifact Registry writer, Cloud Run developer와 해당 runtime account impersonation만 가집니다.
- production 승인은 GitHub `prod` Environment의 required reviewer로 관리합니다.

GitHub numeric ID는 다음처럼 확인합니다.

```bash
gh api repos/ByeongDoo-Han/mypasture-backend --jq '.id'
gh api users/ByeongDoo-Han --jq '.id'
```

## 적용

플랫폼 Terraform이 Artifact Registry와 runtime service account를 만든 뒤 적용합니다.

```bash
cp terraform.tfvars.example terraform.tfvars

terraform init \
  -backend-config="bucket=YOUR_STATE_BUCKET" \
  -backend-config="prefix=mypasture/bootstrap/github-actions"
terraform plan -out=github-actions.tfplan
terraform apply github-actions.tfplan
```

`terraform.tfvars`에는 공개 식별자만 넣으며 API key, 비밀번호와 GitHub token을 넣지 않습니다. 적용 후
`github_environment_variables` output의 값을 GitHub `staging`, `prod` Environment variables에 등록합니다.
`API_HEALTH_URL`은 custom domain이 있으면 해당 URL을 사용하고, 비어 있으면 workflow가 Cloud Run URL을 조회합니다.
