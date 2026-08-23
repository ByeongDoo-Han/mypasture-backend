# My Pasture 초기 MVP 구현 가이드

## 사용자 흐름

1. 이메일 회원가입 또는 로그인 후 JWT를 기기 보안 저장소에 보관한다.
2. 관리자가 원문을 검수해 게시한 오늘의 말씀을 확인하고 66권에서 장을 골라 읽는다.
3. 장을 열거나 완독하면 진행률과 날짜별 읽기 기록이 저장된다.
4. 리더가 현장 QT를 생성해 초대 링크 기반 QR을 목원에게 보여준다.
5. 목원이 QR을 스캔하면 세션 참여와 출석이 한 번만 기록된다.
6. 목원은 묵상을 작성하고 필요하면 같은 세션의 목장에 공유한다.
7. 매일 설정한 시각에 FCM으로 오늘의 말씀 알림을 받는다.
8. AI 목자는 서버가 검색한 성경 구절만 근거로 상담 답변을 제공한다.

## 오프라인 QT 정의

이 MVP에서 오프라인 QT는 인터넷이 없는 상태가 아니라 목원들이 실제 장소에 모이는 현장 모임을 뜻한다. QR 조회와 참여에는 서버 연결이 필요하다. 모바일 로컬 저장소는 작성 중인 묵상 초안 유실 방지에만 사용한다.

## 주요 API

- `PUT /api/v1/reading/progress`
- `GET /api/v1/reading/progress`
- `GET /api/v1/reading/history`
- `GET /api/v1/today-word`
- `POST /api/v1/notifications/devices`
- `GET|PUT /api/v1/notifications/settings/daily-word`
- `POST /api/v1/quiet-time/pastures`
- `POST /api/v1/quiet-time/pastures/{pastureId}/sessions`
- `POST /api/v1/quiet-time/offline/{offlineCode}/join`
- `GET|PUT /api/v1/quiet-time/sessions/{sessionId}/reflection`
- `GET /api/v1/quiet-time/sessions/{sessionId}/reflections/shared`
- `GET /api/v1/attendance/me`
- `POST /api/v1/ai/chat`
- `POST /api/v1/ai/chat/stream`

## 운영 환경 변수

```text
OPENAI_API_KEY=
OPENAI_MODEL=gpt-5-mini
DAILY_WORD_GENERATION_ENABLED=true
DAILY_WORD_PROVIDER=openai
DAILY_WORD_PROMPT_VERSION=daily-word-v1
FIREBASE_ENABLED=true
FIREBASE_PROJECT_ID=
FIREBASE_CREDENTIALS_PATH=/run/secrets/firebase-service-account.json
NOTIFICATION_MAX_ATTEMPTS=5
NOTIFICATION_LEASE_DURATION=2m
NOTIFICATION_INITIAL_BACKOFF=30s
NOTIFICATION_MAX_BACKOFF=1h
```

Firebase를 끈 개발 환경에서는 실제 발송 대신 제목과 토큰 끝자리만 로그로 남긴다. 운영에서는 서비스 계정 JSON을 이미지에 포함하지 말고 Secret Manager 또는 읽기 전용 secret volume으로 주입한다.

## 안전 정책

- AI 입력은 300자로 제한한다.
- RAG 결과가 없으면 답변을 생성하지 않는다.
- 답변에는 서버가 검색한 구절 목록을 별도로 반환한다.
- 자해·타해 위기 표현은 OpenAI를 호출하기 전에 차단하고 즉시 긴급 도움 안내를 반환한다.
- AI 답변은 전문 상담과 목회적 돌봄을 대체하지 않는다고 모바일에 표시한다.
