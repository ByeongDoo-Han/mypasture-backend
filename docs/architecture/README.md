# My Pasture Architecture

이 디렉터리는 My Pasture 백엔드의 현재 아키텍처를 설명하는 기준 문서입니다.
문서 구조는 arc42를 따르고, 구조도는 C4 Model, 기술 결정은 ADR로 관리합니다.

## 빠른 탐색

- [arc42 아키텍처 설명](arc42.md): 목표, 제약, 런타임, 배포, 품질, 위험
- [C4 System Context](c4/01-system-context.md): 사용자와 외부 시스템 경계
- [C4 Container](c4/02-containers.md): API, Worker, Job, 데이터 저장소
- [C4 AI Component](c4/03-ai-components.md): AI 목자 RAG 내부 구성
- [C4 Deployment](c4/04-deployment.md): GCP 배포 노드와 자동배포 흐름
- [ADR 인덱스](adr/README.md): 채택한 기술 결정과 변경 이력

## 상태 표기

| 표기 | 의미 |
| --- | --- |
| `구현` | 코드와 운영 구성에서 확인됨 |
| `부분 구현` | 기본 흐름은 있으나 운영 검증이나 일부 정책이 남음 |
| `계획` | 목표 구조이며 현재 구현으로 간주하면 안 됨 |

## 문서 관리 규칙

1. 시스템 경계나 실행 단위가 바뀌면 C4와 arc42를 함께 갱신합니다.
2. 보안, 데이터, 비용 또는 운영에 영향을 주는 선택은 ADR을 먼저 추가합니다.
3. 과거 ADR을 삭제하거나 내용을 덮어쓰지 않고 새 ADR에서 `Superseded` 처리합니다.
4. 다이어그램은 GitHub에서 바로 렌더링되는 Mermaid를 사용합니다.
5. 클래스 전체를 표현하는 C4 Code 단계는 유지 비용이 커서 작성하지 않습니다.

## 기존 상세 문서

- [기능별 구현 체크리스트](../domain-checklist.md)
- [API 명세](../api-spec.md)
- [보안 강화 내역](../security-hardening.ko.md)
- [AI RAG 운영 가이드](../ai-rag-operations.ko.md)
- [알림 전달 운영 가이드](../notification-delivery-operations.ko.md)
- [GCP Terraform 배포 가이드](../gcp-terraform-deployment.ko.md)
