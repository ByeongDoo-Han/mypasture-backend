# 작업 계약: 결제·환불 1차 원장 (#132)

## 목표와 담당

Coordinator 겸 Backend Foundation 단일 작성자가 결제 권한 원장 및 AI 최종 상태 보호를 구현합니다. 기준 브랜치 main, 작업 브랜치 feat/billing-132-refund-ledger입니다.

## 범위

- billing의 domain/application/infrastructure, 새 V42, AI usage/receipt 전이, 테스트, CI 검증, 한국어 문서.
- 공유 경로인 build.gradle.kts, Flyway, CI는 동일 작성자가 관리합니다.
- 사용자 저장소의 탈퇴 포함 잠금 조회는 기존 거래의 사후 환불 갱신에만 사용합니다.
- 새 REST API/외부 이벤트/환경변수는 없습니다. 따라서 Swagger 및 .http 공개 계약 변경도 없습니다.
- 스토어 증거 검증과 durable webhook, 모바일, 관리자, 실제 결제 활성화는 후속 작업입니다.

## 완료 조건

- [x] 중복/역순 이벤트와 구매 소유권 방어
- [x] 동시 이용권 예약과 완료/실패 경합 보호
- [x] AI 성공 보존 및 실패 후 재완료 차단
- [x] 코드에 한국어 역할 주석 및 후속 연결 계약
- [x] 전체 테스트 217개 및 PostgreSQL 14개 통과
- [x] main 병합 시 staging 자동 배포 및 승인 보호 없음 확인
- [ ] staging 배포 승인 후 CI 통과 PR 병합 (별도 승인)

## 위험

원장 입력 타입은 검증 증거가 아닙니다. 인증된 외부 검증기를 거치기 전 공개 API에 연결하지 않습니다. 계정 탈퇴, 유료 이용권 보존기간, 스토어 상품 및 환경 매핑은 실결제 출시 전 추가 검증이 필요합니다.
