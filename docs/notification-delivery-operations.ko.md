# 푸시 발송 복구 및 재시도 운영 가이드

## 적용 범위

오늘의 말씀 푸시는 사용자 단위가 아니라 활성 기기 단위로 예약합니다. 같은 오늘의 말씀과 기기 조합은 `notification_device_deliveries`에 한 번만 생성되며, 한 사용자의 일부 기기만 실패해도 성공한 기기에는 다시 보내지 않습니다.

기존 `notification_deliveries`는 과거 사용자 단위 감사 기록으로 보존합니다. 신규 발송과 재시도는 `notification_device_deliveries`만 사용합니다.

## 상태 전이

```text
PENDING -> PROCESSING -> SENT
                    -> RETRY_WAIT -> PROCESSING
                    -> DEAD
```

- `PENDING`: 아직 한 번도 외부 전송을 시도하지 않은 작업입니다.
- `PROCESSING`: `lease_token`을 가진 한 서버가 처리 중입니다.
- `RETRY_WAIT`: 일시 오류가 발생해 `next_attempt_at`까지 대기합니다.
- `SENT`: 해당 기기 전송이 성공했습니다.
- `DEAD`: 영구 오류이거나 최대 시도 횟수를 소진했습니다.

`PROCESSING`의 `lease_until`이 지나면 다른 서버가 새 `lease_token`으로 다시 선점합니다. 완료 처리 시 token 일치를 검사하므로, 늦게 끝난 이전 작업자는 새 작업자의 결과를 덮어쓸 수 없습니다.

## 오류 분류

- `UNAVAILABLE`, `QUOTA_EXCEEDED`, `INTERNAL`: 지수 backoff로 재시도합니다.
- `UNREGISTERED`, `SENDER_ID_MISMATCH`: 작업을 `DEAD`로 전환하고 기기를 비활성화합니다.
- 인증·메시지 형식 등 그 밖의 명시적 FCM 오류: 재시도하지 않고 `DEAD`로 전환합니다.
- SDK가 분류하지 못한 전송 오류: 유실을 줄이기 위해 일시 오류로 간주하되 최대 시도 횟수까지만 재시도합니다.

## 기본 설정

| 환경 변수 | 기본값 | 설명 |
|---|---:|---|
| `NOTIFICATION_MAX_ATTEMPTS` | `5` | 최초 시도를 포함한 최대 횟수 |
| `NOTIFICATION_LEASE_DURATION` | `2m` | 작업 선점 유효기간 |
| `NOTIFICATION_INITIAL_BACKOFF` | `30s` | 첫 재시도 대기시간 |
| `NOTIFICATION_MAX_BACKOFF` | `1h` | 최대 재시도 대기시간 |
| `NOTIFICATION_SETTINGS_PAGE_SIZE` | `500` | 알림 설정 조회 페이지 크기 |
| `NOTIFICATION_DISPATCH_BATCH_SIZE` | `100` | 한 번에 선점할 delivery 수 |
| `NOTIFICATION_MAX_BATCHES_PER_RUN` | `20` | 스케줄 한 번의 최대 batch 수 |

## 전달 보장과 한계

- DB 예약, 기기별 성공 상태, lease fencing은 중복 선점과 부분 실패 재전송을 방지합니다.
- FCM 전송 성공 직후 `SENT` 저장 전에 서버가 종료되면 lease 복구 과정에서 같은 기기에 다시 보낼 수 있습니다. 외부 FCM API에 idempotency key가 없으므로 시스템의 전달 보장은 exactly-once가 아니라 at-least-once입니다.
- 푸시 payload에는 개인 묵상이나 상담 내용을 넣지 않고 오늘의 말씀 식별 정보만 넣습니다.

## 운영 모니터링

다음 값은 Micrometer 대시보드 추가 이슈에서 지표화해야 합니다.

- lease가 만료된 `PROCESSING` 수
- `RETRY_WAIT`, `DEAD` 수와 오류 코드별 비율
- 기기 플랫폼별 성공률과 p95 발송 지연
- 비활성화된 토큰 증가율
- 한 스케줄 실행에서 최대 batch 제한에 도달한 횟수

`DEAD` 급증 시 FCM 서비스 장애인지, 인증 설정 오류인지, 앱 토큰 갱신 문제인지 오류 코드별로 먼저 분리합니다. 운영자가 임의로 전체 재발송하면 이미 성공한 기기에 중복 알림이 갈 수 있으므로 기기별 상태를 기준으로 재처리해야 합니다.
