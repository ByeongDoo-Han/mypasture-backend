# 오늘의 말씀 생성·검수·게시 운영 가이드

## 운영 원칙

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

worker가 종료되면 `leaseUntil` 이후 다른 인스턴스가 작업을 회수합니다. 이전 worker는 lease token이 달라 결과를 저장할 수 없습니다. 날짜별 `PENDING/PROCESSING` 부분 유일 인덱스가 다중 서버의 활성 작업 중복을 최종 차단합니다.

## 일일 운영 순서

1. 관리자 화면에서 다음 날 생성 작업이 `COMPLETED`인지 확인합니다.
2. 연결된 성경 원문과 AI 묵상, 실천 질문을 대조합니다.
3. 필요한 경우 문구를 수정하고 사유를 기록합니다.
4. 검수 기준을 충족하면 게시하고, 그렇지 않으면 반려합니다.
5. 게시본이 없는 날짜에는 알림 delivery가 예약되지 않는지 확인합니다.

## 환경변수

```text
DAILY_WORD_GENERATION_ENABLED=true
DAILY_WORD_PROVIDER=openai
DAILY_WORD_GENERATION_CRON=0 0 18 * * *
DAILY_WORD_GENERATION_ZONE=Asia/Seoul
DAILY_WORD_TARGET_DAYS_AHEAD=1
DAILY_WORD_LEASE_DURATION=2m
DAILY_WORD_MAX_ATTEMPTS=3
DAILY_WORD_PROMPT_VERSION=daily-word-v1
DAILY_WORD_MAX_OUTPUT_TOKENS=500
```

로컬 기본값은 자동 생성을 끕니다. 외부 비용 없이 흐름을 확인하려면 `DAILY_WORD_GENERATION_ENABLED=true`, `DAILY_WORD_PROVIDER=local`을 명시합니다. 운영에서는 `openai`만 사용하며 `OPENAI_API_KEY`는 Secret Manager로 주입합니다.

## 장애 대응

- `PENDING` 장기 적체: worker 활성 여부와 DB 연결을 확인합니다.
- `PROCESSING` 장기 유지: lease 만료 후 회수되는지 확인합니다.
- `FAILED`: 오류 원인과 시도 횟수를 확인한 뒤 관리자 재생성을 요청합니다.
- `DAILY_WORD_SOURCE_MISMATCH`: 성경 재적재로 원문이 바뀌었는지 확인하고 새 초안을 생성·검수합니다.
- 게시본 누락: 임의 자동 게시를 하지 말고 검수된 수동 콘텐츠를 준비하거나 해당 날짜 알림을 건너뜁니다.
