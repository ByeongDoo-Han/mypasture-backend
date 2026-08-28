# My Pasture GCP Terraform

이 디렉터리는 서버의 GCP 기반을 코드로 관리합니다.

- `bootstrap/state`: 버전 관리와 공개 차단이 적용된 GCS remote state 버킷
- `bootstrap/github-actions`: GitHub OIDC WIF와 staging/prod 배포 계정
- `modules/platform`: 네트워크, Cloud SQL, Docker Redis VM, Secret Manager, Cloud Run, HTTPS LB, 모니터링
- `environments/staging`: 비용을 낮춘 검증 환경
- `environments/prod`: HA 데이터 계층과 외부 HTTPS Load Balancer를 사용하는 운영 환경

실제 비밀번호와 API 키는 `.tfvars`나 Terraform resource에 넣지 않습니다. 자세한 최초 구축 및 배포 순서는
[`docs/gcp-terraform-deployment.ko.md`](../../docs/gcp-terraform-deployment.ko.md)를 따릅니다.
