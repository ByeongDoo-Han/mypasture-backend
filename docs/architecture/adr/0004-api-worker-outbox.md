# ADR-0004: API·Worker 역할 분리와 durable outbox 사용

- 상태: Accepted
- 결정일: 2026-08-29

## 배경

FCM, email, OpenAI 같은 외부 호출을 사용자 transaction 안에서 수행하면 공급자 지연이나 장애가 API 응답까지 전파됩니다. DB commit 직후 프로세스가 종료되면 단순 `@Async` 작업은 유실될 수 있습니다.

## 결정

같은 애플리케이션 이미지를 API와 Worker runtime role로 실행합니다. API transaction은 전달할 작업을 outbox에 영속화하고 Worker가 lease, 상태, 재시도 정책으로 처리합니다. 예약 생성과 운영 감지도 Worker에서 실행합니다.

## 대안

- Controller 또는 service transaction에서 외부 API 동기 호출
- `@Async` 메모리 작업만 사용
- 초기부터 Kafka나 별도 message broker 도입
- 모든 도메인을 독립 microservice로 분리

## 결과

긍정적 결과:

- 외부 공급자 장애와 API 요청을 분리합니다.
- 프로세스 재시작 후에도 미완료 작업을 복구할 수 있습니다.
- API와 background workload의 scale과 권한을 별도로 관리합니다.

부정적 결과:

- 전달은 일반적으로 at-least-once이므로 consumer idempotency가 필요합니다.
- outbox 적체, lease 만료, dead-letter 성격의 실패를 운영해야 합니다.
- 현재 단일 코드베이스·이미지를 공유하므로 잘못된 runtime 설정을 테스트해야 합니다.
