# ADR-0005: GitHub OIDC와 digest 기반 GCP 배포

- 상태: Accepted
- 결정일: 2026-08-29

## 배경

GitHub Actions가 GCP에 배포하려면 인증이 필요합니다. 장기 서비스 계정 JSON key는 복제·유출·회수 지연 위험이 있고 mutable tag만 사용하면 실제 배포 artifact를 확정하기 어렵습니다.

## 결정

GitHub Actions OIDC와 GCP Workload Identity Federation으로 단기 자격증명을 발급합니다. Provider 조건은 repository owner ID, repository ID, repository name과 `main` ref로 제한합니다. 이미지는 commit SHA로 push하고 생성된 digest로 Migration, Worker, API, Embedding Job을 갱신합니다.

Staging은 `main` CI 성공 후 자동배포하고 Production은 GitHub Environment 승인을 거친 수동 배포로 유지합니다.

## 대안

- GitHub Secret에 GCP 서비스 계정 JSON key 저장
- 개발자 로컬 환경에서 수동 `gcloud` 배포
- `latest` tag로 서비스 업데이트
- CI 통과 전에 직접 배포

## 결과

긍정적 결과:

- 장기 GCP 인증 key를 GitHub에 저장하지 않습니다.
- 배포된 artifact를 digest로 정확히 추적하고 이전 이미지로 복구할 수 있습니다.
- CI, migration, service 배포와 smoke test 순서를 자동으로 강제합니다.

부정적 결과:

- WIF attribute mapping과 IAM 구성이 복잡합니다.
- 이미지 rollback은 이미 적용된 DB migration을 되돌리지 않습니다.
- GitHub Actions와 GCP IAM 장애가 배포 경로에 영향을 줍니다.
