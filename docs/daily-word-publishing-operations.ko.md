# 오늘의 말씀 순환 제공과 예외 복구 운영 가이드

## 기본 운영: 요일별 자동 순환

`DAILY_WORD_ROTATION_ENABLED=true`가 기본입니다. AI 생성이나 매일 관리자 검수 없이 카탈로그에 정의한 범위의 KOR1910 원문을 DB에서 검증한 뒤 날짜별로 한 번 게시합니다.

| 요일 | 제공 방식 |
| --- | --- |
| 월 | 소망 |
| 화 | 감사 |
| 수 | 사랑 |
| 목 | 평안 |
| 금 | 기쁨 |
| 토·일 | 성경 함께 읽기: 주제 태그 없이 문맥 단위로 순서대로 읽기 |

현재 목록은 `DailyWordRotationCatalog`의 주제별 12개(60개)와 창세기 1~29장 문맥 읽기85개, 총145개입니다. 기존 첫24개 entry의 ID·순서·범위는 유지합니다. 각 읽기 단위는20절 이하입니다. 전체 성경을 감정 주제에 강제 분류하지 않습니다. 본문 유형과 주제 태그는 별개이며 읽기 본문에는 빈 태그를 허용합니다. 목록 끝에서는 처음으로 돌아갑니다. 주제 말씀은 최근30일 같은 범위와 겹치지 않는 후보를 우선하며, 읽기 본문은 목록 순서를 보존합니다.

모든 사용자는 한국 날짜별로 같은 저장된 본문을 받습니다. 요청 횟수는 순환 위치에 영향을 주지 않습니다. worker는 오늘·내일 게시본을 준비하고, worker가 놓친 현재 날짜는 공개 조회에서 복구합니다. 과거 또는 내일 이후의 미게시 날짜를 조회해 임의로 생성할 수 없습니다. 원문 누락·빈 절은 다음 유효한 후보로 이동하고 모든 후보가 실패하면 게시하지 않아 기존 사건 감지가 누락을 알립니다.

기존 게시본과 수동/반려 콘텐츠는 보존합니다. 현재/내일의 기존 AI 초안은 순환 본문으로 전환하며 비용·모델 기록을 지우지 않습니다. 활성 AI 작업은 같은 트랜잭션에서 취소합니다. 순환 게시본은 `source=CURATED`, `readingMode`, `topics`, `passageType`, `rotationEntryId`로 구분하고 `reviewedBy/reviewedAt`은 비워 관리자 검수를 가장하지 않습니다.

기존 `verseText`는 첫 절 원문이며 `passageText`는 여러 절 전체 본문입니다. 새 클라이언트는 `passageText ?? verseText`를 표시합니다. `verse`는 시작 절, `endVerse`는 끝 절입니다. 구버전 클라이언트/서버는 첫 절 계약을 계속 사용할 수 있습니다.

내일 게시본을 미리 준비해도 인앱 알림은 한국 날짜 자정부터 노출하고 자정 이전의 미래 날짜 푸시도 예약하지 않습니다. 장애/수동 복구 초안에는 아래 검수·게시 명령을 계속 사용할 수 있지만, 순환 모드의 AI 생성 예약 요청은409로 명시적으로 거절합니다.

## 개발·배포 순서

V42.8 migration → 서버 → 앱/관리자 순서입니다. 기존 V42.7 및 열린 결제 migration V43/V44와 번호 충돌을 피합니다. 기존 migration과 기존 게시본은 수정하지 않습니다. `DAILY_WORD_ROTATION_ENABLED=false`이면 아래의 기존 AI 초안/검수 흐름으로 돌아갑니다. 이미 저장된 순환 게시본은 다시 생성되지 않습니다. 운영 반영은 실제 연결 도메인과 환경에 대한 사용자 승인 범위에 따라 진행합니다.


공개 `GET /api/v1/today-word`의 nullable `readingGuide`는 현재 카탈로그의 순서(`position`, `total`)와 인접 본문(`previous`, `next`)을 제공합니다. 각 참조는 `version`, `bookCode`, `bookName`, `chapter`, `verse`, `endVerse`입니다. 최초/마지막 본문에는 이전/다음 참조가 null이며 날짜별 실제 다음 게시본이나 개인 완독 여부를 의미하지 않습니다. 자동 CURATED 게시본의 버전·카탈로그ID·범위가 일치할 때만 표시하고 legacy/수동/AI 콘텐츠는 null로 유지합니다. 개인 장 완독 표시는 기존 사용자별 읽기 기록의 `completed=true`와 정확한 버전/책/장이 일치할 때만 앱에서 판단합니다.

원문 공급의 GEN30:32~43 및 GEN33:20이 HTML/USFM/VPL 모두 누락되어 현재 연속 목록은 완전성을 확인한29장까지 제공합니다. 같은 번역의 원문 보강과50장까지의 확대는 [후속Issue171](https://github.com/ByeongDoo-Han/mypasture-backend/issues/171)에서 추적합니다.


## 기존 AI 모드와 수동 복구 운영 원칙

- 생성 worker는 매일 다음 날 콘텐츠를 한 번 예약합니다.
- RAG가 찾은 `verse_id`를 현재 `bible_verses` 원문과 비교한 뒤 묵상과 실천 질문만 AI에 생성시킵니다.
- AI 초안은 `DRAFT`이며 관리자 게시 전에는 공개 API와 FCM에서 사용하지 않습니다.
- 게시 직전에도 절 ID, 번역본, 권·장·절과 본문 전체를 다시 검증합니다.
- 생성 요청, 수정, 게시, 반려는 관리자 ID, 사유, `operationId`와 요청 지문으로 감사 기록을 남깁니다.

## 작업 상태

- `PENDING`: worker 대기
- `PROCESSING`: lease를 획득한 worker가 처리 중
- `COMPLETED`: 검증된 초안 저장 완료
- `FAILED`: 최대 시도 초과 또는 복구할 수 없는 생성 실패
- `CANCELLED`: 관리자가 수동 복구를 시작해 더 이상 저장하면 안 되는 작업

worker가 종료되면 `leaseUntil` 이후 다른 인스턴스가 작업을 회수합니다. 이전 worker는 lease token이 달라 결과를 저장할 수 없습니다. 날짜별 `PENDING/PROCESSING` 부분 유일 인덱스가 다중 서버의 활성 작업 중복을 최종 차단합니다.

## 기존 AI 모드의 일일 운영 순서

1. 운영 요약에서 오늘과 내일의 준비 상태, 실패·적체 작업을 확인합니다.
2. 관리자 화면에서 다음 날 생성 작업이 `COMPLETED`인지 확인합니다.
3. 연결된 성경 원문과 AI 묵상, 실천 질문을 대조합니다.
4. 필요한 경우 문구를 수정하고 사유를 기록합니다.
5. 검수 기준을 충족하면 게시하고, 그렇지 않으면 반려합니다.
6. 게시본이 없는 날짜에는 알림 delivery가 예약되지 않는지 확인합니다.

## 운영 요약 API

`GET /api/v1/admin/daily-words/operations-summary?from=YYYY-MM-DD&to=YYYY-MM-DD`는 다음 값을 제공합니다.

- 오늘과 내일의 `MISSING`, `DRAFT`, `PUBLISHED`, `REJECTED` 준비 상태
- 최대 93일 범위의 콘텐츠 상태 수와 오늘 이후 게시가 필요한 날짜
- 생성 작업의 대기, 처리, 완료, 실패 건수와 가장 오래된 활성 작업 시각
- 수동 복구로 취소된 생성 작업 수
- 현재 콘텐츠에 기록된 embedding, prompt, completion token과 예상 비용 합계

콘텐츠는 날짜별 유일 행이므로 최대 93개만 읽습니다. 생성 작업은 누적 행을 반환하지 않고 DB에서 상태별로 집계해 장기 운영 중에도 응답 크기가 증가하지 않습니다. 이 값은 실시간 청구서가 아니라 콘텐츠에 저장된 생성 시점 비용의 운영 추정치입니다.

## 전용 AI 모델 설정

오늘의 말씀 worker는 `DAILY_WORD` use case의 활성 설정을 요청 시작 시 스냅샷으로 읽습니다. 관리자 조회는 `GET /api/v1/admin/ai/runtime-config?useCase=DAILY_WORD`를 사용합니다. AI 목자의 `AI_PASTOR` 설정과 버전 이력이 분리되므로 한 기능의 모델 변경이 다른 기능에 영향을 주지 않습니다.

## 환경변수

```text
DAILY_WORD_ROTATION_ENABLED=false
DAILY_WORD_GENERATION_ENABLED=true
DAILY_WORD_PROVIDER=openai
DAILY_WORD_GENERATION_CRON=0 0 18 * * *
DAILY_WORD_GENERATION_ZONE=Asia/Seoul
DAILY_WORD_TARGET_DAYS_AHEAD=1
DAILY_WORD_LEASE_DURATION=2m
DAILY_WORD_MAX_ATTEMPTS=3
DAILY_WORD_PROMPT_VERSION=daily-word-v1
DAILY_WORD_MAX_OUTPUT_TOKENS=500
DAILY_WORD_INCIDENT_DETECTION_ENABLED=true
DAILY_WORD_INCIDENT_DETECTION_CRON=0 */10 * * * *
DAILY_WORD_INCIDENT_WARNING_TIME=20:00
DAILY_WORD_INCIDENT_CRITICAL_TIME=22:00
DAILY_WORD_INCIDENT_STALLED_THRESHOLD=10m
DAILY_WORD_INCIDENT_EMAIL_DELIVERY=smtp
DAILY_WORD_INCIDENT_EMAIL_FROM=operations@mypasture.app
DAILY_WORD_INCIDENT_EMAIL_MAX_ATTEMPTS=5
```

로컬 기본값은 자동 AI 생성을 끕니다. 기존 AI 초안/검수 흐름을 외부 비용 없이 확인하려면 `DAILY_WORD_ROTATION_ENABLED=false`, `DAILY_WORD_GENERATION_ENABLED=true`, `DAILY_WORD_PROVIDER=local`을 명시합니다. 기존 AI 운영 모드에서는 `openai`만 사용하며 `OPENAI_API_KEY`는 Secret Manager로 주입합니다. 기본 순환 모드는 provider를 호출하지 않습니다.

## 장애 대응

- `PENDING` 장기 적체: worker 활성 여부와 DB 연결을 확인합니다.
- `PROCESSING` 장기 유지: lease 만료 후 회수되는지 확인합니다.
- `FAILED`: 오류 원인과 시도 횟수를 확인한 뒤 관리자 재생성을 요청합니다.
- `DAILY_WORD_SOURCE_MISMATCH`: 성경 재적재로 원문이 바뀌었는지 확인하고 새 초안을 생성·검수합니다.
- 게시본 누락: 임의 자동 게시를 하지 말고 검수된 수동 콘텐츠를 준비하거나 해당 날짜 알림을 건너뜁니다.

## 자동 감지와 이메일 알림

- 오늘 게시본이 없으면 즉시 `CRITICAL` 사건을 만듭니다.
- 내일 게시본은 기본 20시에 `WARNING`, 22시에 `CRITICAL`로 승격합니다.
- 사용할 수 있는 초안이 없을 때 최종 실패 작업과 기준 시간 이상 멈춘 활성 작업을 별도 사건으로 만듭니다.
- 같은 날짜와 유형은 하나의 사건으로 누적합니다. 같은 심각도의 반복 감지는 새 메일을 만들지 않고, 재발하거나 심각도가 올라갈 때만 `notificationVersion`을 증가시킵니다.
- 관리자가 `확인`하면 대응 중임을 표시하고, `해결`하거나 실제 게시본이 생기면 종료합니다.
- 이메일은 incident 트랜잭션에서 직접 보내지 않습니다. 관리자별 outbox를 먼저 저장하고 lease worker가 SMTP를 호출하며 실패 시 재시도합니다.

## 수동 복구 절차

1. 관리자 화면의 운영 사건에서 날짜와 원인을 확인하고 `확인` 처리합니다.
2. 수동 복구 초안에서 DB 성경 검색 결과 중 실제 절을 선택합니다.
3. 묵상, 실천 질문, 복구 사유를 입력하고 초안을 저장합니다.
4. 저장 시 해당 날짜의 `PENDING` 또는 `PROCESSING` AI 작업은 `CANCELLED`가 됩니다.
5. 생성 worker가 이미 외부 요청을 보냈더라도 수동 초안을 AI 결과로 덮어쓸 수 없습니다.
6. 검수 목록에서 원문을 다시 확인해 게시한 뒤 사건이 자동 해결되는지 확인합니다.

게시된 콘텐츠는 수동 초안으로 교체할 수 없습니다. 기존 미게시 콘텐츠 교체는 관리자 화면에서 명시적으로 선택해야 하며, 모든 요청은 `operationId`, 관리자 ID, 사유와 요청 지문을 감사 로그에 남깁니다.

## 이메일 outbox 운영

`GET /api/v1/admin/daily-words/incident-email-deliveries`는 기본 최근 7일, 최대 31일 범위에서 이메일 delivery를 페이지로 조회합니다. `status`, `incidentId`, `from`, `to`, `page`, `size`를 필터로 사용할 수 있습니다.

- `PENDING`: worker가 곧 선점할 작업
- `PROCESSING`: worker가 lease를 가진 작업이며 관리자 재처리 금지
- `RETRY_WAIT`: 일시 실패 후 자동 재시도 대기, 원인 조치 후 수동 재처리 가능
- `SENT`: 발송 완료, 재처리 금지
- `DEAD`: 최대 자동 시도 후 최종 실패, 원인 조치 후 수동 재처리 가능

응답은 전체 이메일 대신 마스킹된 수신자만 제공합니다. 외부 provider 오류에 포함된 이메일, token, API key, password 형태는 저장 전 제거하고 API에서도 다시 제거합니다.

`POST /api/v1/admin/daily-words/incident-email-deliveries/{deliveryId}/requeue`는 `DEAD` 또는 `RETRY_WAIT`만 즉시 `PENDING`으로 전환합니다. 기존 `attemptCount`를 초기화하지 않으므로 실패 이력은 유지됩니다. 같은 `operationId`와 요청 내용 재전송은 멱등하게 처리하며, 다른 요청에 ID를 재사용하면 충돌을 반환합니다. SMTP 자격증명이나 provider 장애를 해결하지 않은 채 반복 재처리하지 않습니다.
