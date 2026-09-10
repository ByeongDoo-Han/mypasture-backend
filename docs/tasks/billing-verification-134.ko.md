# 작업 계약: 구매 검증 (#134)

Coordinator/Backend Foundation 단일 작성. 최신 main에서 시작하고 미병합 #133의 검증된 의존 변경을 포함합니다. 공유 경로는 동일 작성자가 관리합니다.

## 범위

- billing 검증 어댑터, 영속 작업/계정 매핑, 인증 API, 알림, AI 권한 연결
- V43 추가, 환경 설정 예제, Swagger interface, .http, 한글 운영 문서
- 앱 구매 UI, 유료 심층상담 세션, 실제 과금/배포는 제외

## 완료 조건

- [x] Apple/Google 구매 및 소유권/상품/환경 검증 어댑터와 fixture 테스트
- [x] 재시도, 중복 지급 방지, 늦은 작업 fencing, 구매 승인 별도 재시도
- [x] 알림 인증 및 재동기화 코드 (실제 스토어 성공 알림은 미검증)
- [x] 테스트/빌드/문서 결과 기록

실제 스토어 키와 테스트 상품이 없으면 실구매 검증은 미완료로 보고합니다. disabled 기본값을 유지하고 main 자동 staging 배포는 별도 승인 전 실행하지 않습니다.

## 로컬 최종 검증 (2026-09-11)

- `./gradlew test billingPostgresTest bootJar --no-daemon`: 성공
- 전체 테스트 233개, 실패/오류/스킵 0개
- PostgreSQL 14.15 테스트 22개, 실패/오류/스킵 0개: 원장 13개, 검증 작업/API 8개, V42/V43 Flyway 및 제약 1개
- HTTP private 환경 예제 JSON 파싱 및 `git diff --check`: 성공
- 테스트 전용 힙 기본 512MB에서 메모리 부족을 확인하여 1GB로 명시하고 전체 재실행 성공. 운영 JVM 설정은 변경하지 않음
- 실제 스토어 구매/인증키/과금 API, GCP 배포는 실행하지 않음
- Docker daemon이 꺼져 있어 로컬 이미지는 빌드하지 않았으며 CI의 container-build로 확인 예정
- 기존 #133과 이번 브랜치의 기반 커밋 트리 차이가 없어 선행 변경 누락 없음

운영 설정과 미완료 출시 항목: [구매 검증 가이드](../store-purchase-verification.ko.md).
