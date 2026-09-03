# Architecture Decision Records

ADR은 보안, 데이터, 비용, 운영 또는 시스템 경계에 영향을 주는 결정을 기록합니다.

| ADR | 상태 | 결정 |
| --- | --- | --- |
| [ADR-0001](0001-domain-first-clean-architecture.md) | Accepted | 도메인 우선 Clean Architecture 패키지 사용 |
| [ADR-0002](0002-postgresql-pgvector-server-rag.md) | Accepted | PostgreSQL·pgvector 기반 서버 RAG 사용 |
| [ADR-0003](0003-stateless-jwt-social-token-verification.md) | Accepted | Stateless JWT와 소셜 token 서버 검증 |
| [ADR-0004](0004-api-worker-outbox.md) | Accepted | API·Worker 역할 분리와 durable outbox 사용 |
| [ADR-0005](0005-github-oidc-immutable-deployment.md) | Accepted | GitHub OIDC와 digest 기반 GCP 배포 |

## 작성 규칙

- 파일명: `NNNN-short-title.md`
- 상태: `Proposed`, `Accepted`, `Deprecated`, `Superseded`, `Rejected`
- 필수 항목: 배경, 결정, 대안, 결과
- 기존 결정을 바꿀 때 새 ADR을 만들고 이전 ADR의 상태와 대체 링크를 갱신합니다.

[아키텍처 인덱스로 돌아가기](../README.md)
