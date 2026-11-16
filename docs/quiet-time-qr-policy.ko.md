# 큐티 모임 QR 만료 및 폐기 정책

이 문서는 My Pasture 큐티 모임 QR의 발급, 참여 가능 시간, 만료, 재발급, 폐기와 동시성 처리 기준을 정리합니다.

## 현재 적용된 기본 정책

- 큐티 모임 QR은 모임 종료 후 30분에 자동 만료됩니다.
- 모임 시간을 전달하지 않으면 생성 시점부터 2시간을 기본 모임 시간으로 사용합니다.
- joinStartsAt을 지정하지 않으면 QR 생성 즉시 참여할 수 있습니다.
- 모든 시간 판정은 모바일 시간이 아니라 백엔드 서버의 UTC 시간을 기준으로 합니다.
- 서버는 QR 이미지를 생성하거나 저장하지 않고 HTTPS inviteUrl만 반환합니다.
- 새 초대 주소는 실제 웹 앱의 `https://app.mypasture.app/q/qt/{offlineCode}`를 사용합니다.
- 모바일과 관리자 프론트는 inviteUrl을 이용해 QR 이미지를 렌더링합니다.
- DB에는 세션과 별도로 QR 발급 이력, offlineCode, inviteUrl, joinStartsAt, expiresAt, revokedAt을 저장합니다.
- 만료되거나 폐기된 QR도 모임 정보와 상태는 조회할 수 있지만 참여 요청은 거부합니다.

## QR 상태

| 상태 | 의미 | 모임 정보 조회 | 참여 |
|---|---|---:|---:|
| SCHEDULED | 참여 시작 시간 전 | 가능 | 불가 |
| OPEN | 참여 가능 시간 | 가능 | 가능 |
| EXPIRED | 만료 시간 경과 | 가능 | 불가 |
| REVOKED | 리더 폐기 또는 재발급으로 교체 | 가능 | 불가 |
| CLOSED | 큐티 모임 종료 | 가능 | 불가 |

| HTTP | 오류 코드 | 상황 |
|---:|---|---|
| 409 | QUIET_TIME_QR_NOT_OPEN | 참여 시작 전 |
| 410 | QUIET_TIME_QR_EXPIRED | 유효기간 종료 |
| 410 | QUIET_TIME_QR_REVOKED | 폐기 또는 재발급된 구 QR |
| 410 | QUIET_TIME_SESSION_CLOSED | 모임 종료 |
| 403 | QUIET_TIME_LEADER_REQUIRED | 리더가 아닌 사용자의 QR 관리 요청 |

## 재발급과 즉시 폐기

- 리더만 QR을 재발급하거나 폐기할 수 있습니다.
- 재발급하면 같은 세션의 기존 활성 QR을 즉시 폐기하고 새 offlineCode를 생성합니다.
- 이전 호스트로 이미 출력한 QR은 주소 자체를 바꿀 수 없으므로 리더가 재발급해야 합니다. 6자리 참여 코드는 기존 방식대로 사용할 수 있습니다.
- 프론트에서 생성한 QR 이미지를 캡처하거나 저장했더라도 서버에서 REVOKED로 판정하므로 참여할 수 없습니다.
- QR만 폐기하면 세션은 유지되며 리더가 다시 발급할 수 있습니다.
- 세션을 종료하면 활성 QR을 모두 폐기하고 이후 재발급과 참여를 막습니다.

## 인증 및 권한

- QR 스캔 후 모임 정보 조회와 참여는 비회원도 사용할 수 있습니다.
- 목장 생성, 세션 생성, QR 재발급, QR 폐기, 세션 종료는 JWT 인증이 필요합니다.
- QR 관리 요청에서는 JWT 사용자 ID와 목장의 leaderUserId가 일치하는지 확인합니다.
- 이전에 생성되어 leaderUserId가 없는 데이터는 관리자 이관 전까지 QR 관리 기능을 사용할 수 없습니다.

## 동시성 정책

- 참여 요청은 QR 초대 행에 PESSIMISTIC_WRITE 잠금을 획득한 뒤 상태를 다시 확인합니다.
- QR 폐기, 재발급, 세션 종료도 세션 또는 QR 행 잠금 안에서 처리합니다.
- 참여와 폐기가 동시에 요청되면 잠금을 먼저 획득한 트랜잭션 순서대로 처리됩니다.
- session_id + device_id unique constraint로 동일 기기의 중복 참여를 방지합니다.
- 같은 기기가 다시 요청하면 기존 참여 정보와 joinedAlready: true를 반환합니다.

## API

리더 API:

- POST /api/v1/quiet-time/pastures/{pastureId}/sessions
- POST /api/v1/quiet-time/sessions/{sessionId}/qr/reissue
- DELETE /api/v1/quiet-time/sessions/{sessionId}/qr
- POST /api/v1/quiet-time/sessions/{sessionId}/close

공개 스캔 API:

- GET /api/v1/quiet-time/offline/{offlineCode}
- POST /api/v1/quiet-time/offline/{offlineCode}/join

## 운영 전에 추가로 결정할 사항

- 일반 일회성 큐티 QR 외에 목장 장기 초대 QR을 별도 타입으로 제공할지 결정해야 합니다.
- 장기 초대 QR은 7일 또는 30일 만료를 권장하며 당일 참석 QR과 정책을 분리해야 합니다.
- 참여 정원을 지원할 경우 정원 확인과 참여 저장을 같은 잠금 범위에서 처리해야 합니다.
- QR 캡처 공유를 제한해야 하는 모임에는 리더 승인제나 추가 PIN을 적용할 수 있습니다.
- IP와 기기 기준 참여 API rate limit을 추가해야 합니다.
- 모바일은 `/q/qt/{offlineCode}` 경로 또는 6자리 참여 코드를 해석하고, 웹은 `app.mypasture.app`에서 로그인·동의 후 세션으로 연결합니다.
