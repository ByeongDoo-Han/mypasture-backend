# My Pasture API Specification

기준 버전: `v1`

Base URL:

```text
/api/v1
```

이 문서는 My Pasture에서 요구한 모바일, 관리자, AI, 목장, 기도, 챌린지, 알림, 달란트 기능을 REST API 기준으로 정리한 명세서입니다. 상태 표기는 다음을 사용합니다.

- `IMPLEMENTED`: 현재 코드에 구현됨
- `PARTIAL`: 일부 구현됨
- `PLANNED`: 설계 대상, 구현 필요

## Common Rules

### Authentication

로그인 이후 API는 기본적으로 Bearer JWT를 사용합니다.

```http
Authorization: Bearer {accessToken}
```

예외적으로 아래 API는 인증 없이 접근 가능합니다.

```text
GET  /api/v1/auth/health
POST /api/v1/auth/email-verifications
POST /api/v1/auth/email-verifications/confirm
POST /api/v1/auth/signup
POST /api/v1/auth/login
POST /api/v1/auth/social-login
POST /api/v1/auth/social-login/challenges
POST /api/v1/auth/refresh
POST /api/v1/auth/password-reset/requests
POST /api/v1/auth/password-reset/confirm
GET  /api/v1/policies/current
GET  /api/v1/policies/{type}/{version}
```

### Content Type

일반 API:

```http
Content-Type: application/json
Accept: application/json
```

AI streaming API:

```http
Accept: text/event-stream
```

### Standard Error Response

```json
{
  "code": "ERROR_CODE",
  "message": "사용자에게 표시 가능한 메시지",
  "details": {
    "field": "optional"
  },
  "traceId": "optional-request-trace-id"
}
```

Common error codes:

| HTTP | code | 설명 |
|---:|---|---|
| 400 | `VALIDATION_FAILED` | 요청 값 검증 실패 |
| 401 | `UNAUTHORIZED` | 인증 필요 또는 토큰 만료 |
| 403 | `FORBIDDEN` | 권한 부족 |
| 404 | `NOT_FOUND` | 리소스 없음 |
| 409 | `CONFLICT` | 중복 또는 상태 충돌 |
| 429 | `RATE_LIMITED` | 요청 제한 초과 |
| 500 | `INTERNAL_ERROR` | 서버 오류 |

### Pagination

목록 API는 cursor pagination을 기본으로 합니다.

Request query:

```text
?cursor={opaqueCursor}&limit=20
```

Response:

```json
{
  "items": [],
  "nextCursor": "opaque-next-cursor",
  "hasNext": true
}
```

### Idempotency

결제성/보상성/중복 실행 위험이 있는 API는 idempotency key를 받습니다.

```http
Idempotency-Key: client-generated-uuid
```

대상:

```text
POST /api/v1/rewards/exchanges
POST /api/v1/challenges/{challengeId}/claim-reward
POST /api/v1/admin/push-campaigns
```

## 1. Auth API

### 1.1 Health

Status: `IMPLEMENTED`

```http
GET /api/v1/auth/health
```

Response `200`: `{"status":"auth-ready"}`

### 1.2 Request email verification

Status: `IMPLEMENTED`

```http
POST /api/v1/auth/email-verifications
Content-Type: application/json

{
  "email": "user@example.com"
}
```

Response `202`는 계정 존재 여부와 관계없이 동일합니다.

```json
{
  "message": "인증 가능한 이메일이라면 인증번호를 발송했습니다.",
  "expiresInSeconds": 600,
  "resendAfterSeconds": 60
}
```

### 1.3 Confirm email verification

Status: `IMPLEMENTED`

```http
POST /api/v1/auth/email-verifications/confirm
Content-Type: application/json

{
  "email": "user@example.com",
  "code": "123456"
}
```

Response `200`의 `verificationToken`은 같은 이메일 회원가입에 한 번만 사용할 수 있습니다.

```json
{
  "verificationToken": "one-time-signup-token",
  "expiresAt": "2026-08-22T01:00:00Z"
}
```

Errors: `400 INVALID_EMAIL_VERIFICATION_CODE`.

### 1.4 Email signup

Status: `IMPLEMENTED`

```http
POST /api/v1/auth/signup
Content-Type: application/json

{
  "email": "user@example.com",
  "password": "password123!",
  "displayName": "홍길동",
  "verificationToken": "one-time-signup-token",
  "policyConsents": [
    { "type": "TERMS_OF_SERVICE", "version": "1.0.0", "agreed": true },
    { "type": "PRIVACY_COLLECTION", "version": "1.0.0", "agreed": true },
    { "type": "MARKETING_NOTIFICATIONS", "version": "1.0.0", "agreed": false }
  ]
}
```

Validation: email 최대 320자, password 8-72자, displayName 1-80자.

Response `201`:

```json
{
  "user": {
    "id": "uuid",
    "email": "user@example.com",
    "displayName": "홍길동",
    "role": "USER",
    "subscriptionTier": "FREE",
    "createdAt": "2026-08-13T00:00:00Z",
    "updatedAt": "2026-08-13T00:00:00Z"
  }
}
```

Errors: `400 EMAIL_VERIFICATION_REQUIRED`, `400 POLICY_CONSENT_REQUIRED`, `409 EMAIL_ALREADY_REGISTERED`.

### 1.5 Email login

Status: `IMPLEMENTED`

```http
POST /api/v1/auth/login
Content-Type: application/json

{
  "email": "user@example.com",
  "password": "password123!"
}
```

Response `200`:

```json
{
  "token": "jwt-access-token",
  "tokenType": "Bearer",
  "expiresIn": 3600,
  "expiresAt": "2026-08-13T01:00:00Z",
  "user": {}
}
```

Errors: `401 INVALID_CREDENTIALS`.

### 1.6 Social login

Status: `IMPLEMENTED`

```http
POST /api/v1/auth/social-login
Content-Type: application/json

{
  "provider": "GOOGLE",
  "idToken": "google-or-apple-id-token",
  "accessToken": null,
  "displayName": "optional"
}
```

- Google/Apple: `idToken` 전달
- Kakao: `accessToken` 전달
- provider: `GOOGLE | APPLE | KAKAO`

Response `200`: Email login과 동일한 JWT 응답.

### 1.7 Request password reset

Status: `IMPLEMENTED`

```http
POST /api/v1/auth/password-reset/requests
Content-Type: application/json

{
  "email": "user@example.com"
}
```

Response `202`:

```json
{
  "message": "가입된 이메일이라면 비밀번호 재설정 안내를 발송했습니다."
}
```

이메일 존재 여부와 무관하게 같은 응답을 반환한다. EMAIL 계정에만 30분 유효한 일회성 토큰을 발급하며 동일 계정은 기본 60초 cooldown을 적용한다.

### 1.8 Confirm password reset

Status: `IMPLEMENTED`

```http
POST /api/v1/auth/password-reset/confirm
Content-Type: application/json

{
  "token": "raw-reset-token-from-email",
  "newPassword": "new-password123!"
}
```

Response `204`. 완료 시 기존 JWT가 모두 무효화된다.

Errors:

| HTTP | code |
|---:|---|
| 400 | `INVALID_PASSWORD_RESET_TOKEN` |
| 400 | `PASSWORD_REUSE_NOT_ALLOWED` |

### 1.9 Current user

Status: `IMPLEMENTED`

```http
GET /api/v1/auth/me
Authorization: Bearer <access-token>
```

Response `200`: current user profile.

### 1.10 Update current user profile

Status: `IMPLEMENTED`

```http
PATCH /api/v1/auth/me
Authorization: Bearer <access-token>
Content-Type: application/json

{
  "displayName": "새 이름"
}
```

Response `200`: 변경된 current user profile. 표시 이름의 앞뒤 및 연속 공백을 정리하며 최대 40자까지 허용한다. 이메일 변경은 재인증, 중복 확인, 이메일 인증 및 소셜 계정 연결 정책이 필요하므로 이 API에서 제공하지 않는다. 프로필 변경만으로 기존 JWT를 폐기하지 않는다.

### 1.11 Delete account

Status: `IMPLEMENTED`

```http
DELETE /api/v1/auth/me
Authorization: Bearer <access-token>
Content-Type: application/json

{
  "confirmation": "DELETE",
  "currentPassword": "password123!"
}
```

EMAIL 계정은 현재 비밀번호가 필수다. 소셜 전용 계정은 `currentPassword`를 생략한다. Response `204`.

탈퇴 시 로그인 제공자 연결, token, AI·개인 신앙 기록, 말씀 이용 기록과 알림 정보를 물리 삭제한다. 공동 QT 모임은 유지하되 작성자·기기 정보를 익명화하고, 사용자 행은 참조 무결성용 익명 tombstone으로 남긴다. 전체 DB 처리는 한 트랜잭션이며 기존 JWT는 즉시 무효화되고 동일 이메일로 재가입할 수 있다. 상세 정책은 `docs/account-erasure-policy.ko.md`를 따른다.

Errors:

| HTTP | code |
|---:|---|
| 400 | `INVALID_DELETION_CONFIRMATION` |
| 401 | `REAUTHENTICATION_FAILED` |
| 401 | `UNAUTHORIZED` |

### 1.12 Refresh token / logout

Status: `IMPLEMENTED`

Access token 외 refresh token 회전, 재사용 탐지, 서버 측 로그아웃은 아직 구현되지 않았다.

## 2. User/Profile API

### 2.1 My Profile

Status: `PLANNED`

```http
GET /api/v1/users/me
```

Response `200`:

```json
{
  "id": "uuid",
  "email": "user@example.com",
  "displayName": "홍길동",
  "role": "USER",
  "subscriptionTier": "FREE",
  "stats": {
    "xp": 1250,
    "talents": 320,
    "streakDays": 12,
    "completedChapters": 48
  }
}
```

### 2.2 Update Profile

Status: `PLANNED`

```http
PATCH /api/v1/users/me
```

Request:

```json
{
  "displayName": "새 이름",
  "profileImageUrl": "https://cdn.example.com/profile.png"
}
```

Response `200`: user profile

### 2.3 Reading Calendar

Status: `PLANNED`

```http
GET /api/v1/users/me/reading-calendar?from=2026-08-01&to=2026-08-31
```

Response `200`:

```json
{
  "days": [
    {
      "date": "2026-08-03",
      "read": true,
      "chapters": 3,
      "minutes": 18
    }
  ]
}
```

### 2.4 Achievements

Status: `PLANNED`

```http
GET /api/v1/users/me/achievements
```

Response `200`:

```json
{
  "items": [
    {
      "id": "first-prayer",
      "title": "첫 기도",
      "description": "첫 기도제목을 작성했습니다.",
      "earnedAt": "2026-08-03T00:00:00Z"
    }
  ]
}
```

## 3. Bible API

### 3.1 Bible Books

Status: `IMPLEMENTED`

```http
GET /api/v1/bibles/{version}/books
```

Response `200`:

```json
{
  "items": [
    {
      "id": 1,
      "code": "GEN",
      "name": "창세기",
      "testament": "OLD",
      "displayOrder": 1
    }
  ]
}
```

### 3.2 Bible Chapter

Status: `IMPLEMENTED`

```http
GET /api/v1/bibles/{version}/books/{bookCode}/chapters/{chapter}
```

Response `200`:

```json
{
  "version": "KOR1910",
  "book": {
    "code": "GEN",
    "name": "창세기",
    "testament": "OLD",
    "displayOrder": 1,
    "chapterCount": 50
  },
  "chapter": 1,
  "contentStatus": "AVAILABLE",
  "previous": null,
  "next": {
    "bookCode": "GEN",
    "bookName": "창세기",
    "chapter": 2
  },
  "verses": [
    {
      "version": "KOR1910",
      "bookCode": "GEN",
      "bookName": "창세기",
      "chapter": 1,
      "verse": 1,
      "text": "태초에 하나님이 천지를 창조하시니라"
    }
  ]
}
```

`contentStatus`는 `AVAILABLE`, `EMPTY`, `SOURCE_MISSING` 중 하나입니다. `EMPTY` 장은 존재하지만
원본 파일이 없는 상태이므로 `200 OK`와 빈 `verses` 배열을 반환합니다.

### 3.3 Bible Search

Status: `IMPLEMENTED`

```http
GET /api/v1/bibles/{version}/search?keyword=사랑&page=0&size=20
```

Response `200`:

```json
{
  "items": [
    {
      "verseId": 1,
      "version": "KRV",
      "book": "요한복음",
      "chapter": 3,
      "verse": 16,
      "text": "...",
      "highlightedText": "..."
    }
  ],
  "nextCursor": null,
  "hasNext": false
}
```

### 3.4 Bible Chapter Commentary

Status: `IMPLEMENTED`

사용자 조회 시 AI를 호출하지 않고 검수 후 게시된 최신 해설만 반환한다.

```http
GET /api/v1/bibles/{version}/books/{bookCode}/chapters/{chapter}/commentary
```

게시된 해설이 없거나 원문 해시가 변경된 경우 `404 BIBLE_COMMENTARY_NOT_FOUND`를 반환한다.

관리자 생성 및 검수 API:

```http
POST /api/v1/admin/bible-commentaries/generation-jobs
POST /api/v1/admin/bible-commentaries/generation-jobs/ranges
GET  /api/v1/admin/bible-commentaries/coverage?version={version}&bookCode={bookCode}
GET  /api/v1/admin/bible-commentaries/generation-jobs
GET  /api/v1/admin/bible-commentaries
POST /api/v1/admin/bible-commentaries/{commentaryId}/publish
POST /api/v1/admin/bible-commentaries/{commentaryId}/reject
```

상세 운영 절차는 `docs/bible-commentary-pipeline.ko.md`를 따른다.

### 3.5 Highlights

Status: `PLANNED`

```http
POST /api/v1/bible-highlights
```

Request:

```json
{
  "verseId": 1,
  "color": "YELLOW",
  "note": "묵상 메모"
}
```

Response `201`:

```json
{
  "id": "uuid",
  "verseId": 1,
  "color": "YELLOW",
  "note": "묵상 메모",
  "createdAt": "2026-08-03T00:00:00Z"
}
```

Other endpoints:

```http
GET    /api/v1/bible-highlights
PATCH  /api/v1/bible-highlights/{highlightId}
DELETE /api/v1/bible-highlights/{highlightId}
```

### 3.6 Bookmarks

Status: `IMPLEMENTED`

모든 API는 Bearer access token이 필요합니다. 북마크는 `(user_id, verse_id)`로
고유하므로 같은 `PUT` 요청을 반복해도 결과가 하나로 유지됩니다.

```http
GET    /api/v1/bookmarks?page=0&size=50
GET    /api/v1/bookmarks/status?version=KOR1910&bookCode=GEN&chapter=1
PUT    /api/v1/bookmarks/{version}/{bookCode}/{chapter}/{verse}
DELETE /api/v1/bookmarks/{version}/{bookCode}/{chapter}/{verse}
```

저장 응답:

```json
{
  "id": "c8c4c79c-5cbf-4fa2-a8b3-799df0b485f0",
  "version": "KOR1910",
  "bookCode": "GEN",
  "bookName": "창세기",
  "chapter": 1,
  "verse": 1,
  "text": "태초에 하나님이 천지를 창조하시니라",
  "createdAt": "2026-08-23T00:00:00Z"
}
```

목록은 최신 저장순이며 `size`는 1부터 100까지 허용합니다. 장별 상태 API는
`{ "verseNumbers": [1, 3, 7] }`만 반환하므로 전체 저장 목록을 읽지 않고도
본문에 저장 상태를 표시할 수 있습니다.

### 3.7 Reading Progress

Status: `PLANNED`

```http
PUT /api/v1/bible-progress
```

Request:

```json
{
  "version": "KRV",
  "bookId": 1,
  "chapter": 1,
  "completed": true,
  "lastVerse": 31
}
```

Response `200`: progress object

### 3.8 Reading History

Status: `PLANNED`

```http
POST /api/v1/bible-reading-history
GET /api/v1/bible-reading-history?from=2026-08-01&to=2026-08-31
```

### 3.9 Offline Sync

Status: `PLANNED`

```http
GET /api/v1/offline/bible-sync?version=KRV&since=2026-08-01T00:00:00Z
```

Response `200`:

```json
{
  "version": "KRV",
  "books": [],
  "verses": [],
  "serverTime": "2026-08-03T00:00:00Z"
}
```

## 4. AI Assistant API

### 4.1 Ask AI Assistant

Status: `IMPLEMENTED`

```http
POST /api/v1/ai/chat
```

Rules:

- Input max: 300 chars
- Free user quota: 10 requests/day
- AI must use RAG citations
- AI must not invent Bible verses
- Classify the question as counseling, Bible explanation, prayer, verse recommendation, devotional, or Bible Q&A before retrieval
- Resolve an explicit book/chapter/verse from the Bible DB before semantic search
- Store prompt tokens, completion tokens, estimated cost
- Redis cache should be checked before OpenAI call
- Response contract: `assistant-response-v2`
- Free: one-shot answer, verified scripture blocks, and a recommended prayer
- Premium: the same response contract plus an encrypted, expiring guided conversation and one focused follow-up question

Request:

```json
{
  "question": "불안합니다. 어떤 말씀을 묵상하면 좋을까요?",
  "conversationId": null
}
```

Response `200`:

```json
{
  "answer": "검색된 성경 구절을 근거로 답변합니다...",
  "citations": [
    {
      "verseId": 1001,
      "version": "KRV",
      "book": "시편",
      "bookCode": "PSA",
      "chapter": 23,
      "verse": 1,
      "text": "..."
    }
  ],
  "promptTokens": 123,
  "completionTokens": 456,
  "embeddingTokens": 18,
  "estimatedCostUsd": 0.000123,
  "embeddingCostUsd": 0.00000036,
  "retrievalStrategy": "PGVECTOR",
  "questionIntent": "PASTORAL_COUNSELING",
  "cacheHit": false,
  "schemaVersion": "assistant-response-v2",
  "conversationId": null,
  "turnId": "uuid",
  "status": "COMPLETED",
  "interactionMode": "ONE_SHOT",
  "blocks": [
    { "type": "ANSWER", "text": "..." },
    { "type": "SCRIPTURE", "verseId": 1001, "version": "KOR1910", "bookCode": "PSA", "reference": "시편 23:1", "text": "...", "action": "OPEN_BIBLE" },
    { "type": "PRAYER", "title": "불안을 맡기는 기도", "content": "..." }
  ],
  "nextAction": "COMPLETE"
}
```

프리미엄 응답에는 `conversationId`, `interactionMode=GUIDED_DIALOGUE`, `FOLLOW_UP_QUESTION` block과
`nextAction=WAIT_USER_INPUT`이 포함됩니다. 다음 요청에 같은 `conversationId`를 보내면 최근 최대 4개 turn을
문맥으로 사용합니다. 문맥은 AES-GCM으로 암호화하고 30일 뒤 삭제하며, 다른 사용자의 ID는 `404`로 처리합니다.

### 4.2 Bible Embedding Index Admin

Status: `IMPLEMENTED`

```http
GET  /api/v1/admin/ai/embeddings
POST /api/v1/admin/ai/embeddings/index-next-batch
```

두 API 모두 `ADMIN` 권한이 필요합니다. GET은 전체 절, 현재 모델로 index된 절, 남은 절 수를 반환하고,
POST는 분산 lock을 획득한 서버만 설정된 크기의 단일 batch를 처리합니다.

### 4.3 Streaming AI Assistant

Status: `IMPLEMENTED`

```http
POST /api/v1/ai/chat/stream
Accept: text/event-stream
```

정상 완료 이벤트 순서:

```text
event: status
data: {"stage":"UNDERSTANDING_QUESTION"}

event: status
data: {"stage":"SEARCHING_SCRIPTURE"}

event: status
data: {"stage":"GENERATING_ANSWER"}

event: delta
data: 근거: [1001] 주님은

event: response
data: {"schemaVersion":"assistant-response-v2","blocks":[...],"nextAction":"COMPLETE"}

event: citations
data: [{"verseId":1001,"book":"시편","chapter":23,"verse":1}]

event: usage
data: {"promptTokens":123,"completionTokens":456,"embeddingTokens":18,"estimatedCostUsd":0.000123,"retrievalStrategy":"PGVECTOR","questionIntent":"PASTORAL_COUNSELING","cacheHit":false}

event: done
data: {"status":"completed"}
```

### 4.4 Premium Guided Conversations

Status: `IMPLEMENTED`

첫 프리미엄 `/chat` 또는 `/chat/stream` 요청은 대화를 자동 생성합니다. 후속 요청은 응답의
`conversationId`를 그대로 전달합니다. 대화 목록·장기 보관·내보내기 API는 개인정보 정책 확정 전까지 제공하지 않습니다.

### 4.5 Daily Meditation AI Batch

Status: `IMPLEMENTED`

매일 다음 날 초안을 한 번 예약하고 worker가 RAG 검색, 원문 재검증, Structured Output 생성을 수행합니다. 초안은 관리자 게시 전에는 공개되지 않습니다.

```http
POST /api/v1/admin/daily-words/{date}/generation-jobs
GET  /api/v1/admin/daily-words/generation-jobs
```

### 4.6 Answer Feedback and Improvement

Status: `IMPLEMENTED`

```http
POST /api/v1/ai/feedback
POST /api/v1/ai/feedback/{turnId}/regenerate
GET  /api/v1/admin/ai/feedback/summary
GET  /api/v1/admin/ai/feedback/priority?limit=20
```

- `HELPFUL`은 사유 없이, `NOT_HELPFUL`은 1~3개 사유와 선택 의견 300자 이하로 보냅니다.
- 같은 사용자와 `turnId`의 최종 평가는 한 건으로 갱신됩니다.
- 피드백 제출은 AI 사용량을 차감하지 않습니다.
- 원래 질문과 HMAC이 일치하고 `NOT_HELPFUL`인 답변만 한 번 무료로 개선 생성할 수 있습니다.
- 개선 생성의 토큰·비용은 별도 usage log에 기록하지만 `quota_counted=false`입니다.
- 관리자 조회에는 질문, 답변, 자유 의견을 반환하지 않고 집계와 잘못된 구절 신고의 추적 ID만 제공합니다.

## 5. Today's Word API

### 5.1 Get Today's Word

Status: `IMPLEMENTED`

```http
GET /api/v1/today-word?date=2026-08-03
```

Response `200`:

```json
{
  "id": "uuid",
  "date": "2026-08-03",
  "version": "KOR1910",
  "bookCode": "PSA",
  "bookName": "시편",
  "chapter": 23,
  "verse": 1,
  "verseText": "...",
  "meditation": "오늘의 묵상",
  "actionQuestion": "오늘 이 말씀을 어디에 적용할 수 있나요?",
  "publishedAt": "2026-08-02T10:00:00Z"
}
```

게시본이 없으면 `404 DAILY_WORD_NOT_PUBLISHED`, 저장된 본문이 현재 `bible_verses` 원문과 다르면 `422 DAILY_WORD_SOURCE_MISMATCH`를 반환합니다. 공개 요청에서는 AI를 호출하지 않습니다.

### 5.2 Today Word Notification Settings

Status: `IMPLEMENTED`

```http
GET /api/v1/notifications/settings/daily-word
PUT /api/v1/notifications/settings/daily-word
```

Request:

```json
{
  "enabled": true,
  "notificationTime": "07:00:00",
  "timezone": "Asia/Seoul"
}
```

## 6. Challenge API

### 6.1 Challenge List

Status: `PLANNED`

```http
GET /api/v1/challenges?type=DAILY&status=ACTIVE
```

Response `200`:

```json
{
  "items": [
    {
      "id": "uuid",
      "title": "시편 1편 읽기",
      "type": "DAILY",
      "startsAt": "2026-08-03T00:00:00Z",
      "endsAt": "2026-08-03T23:59:59Z",
      "rewardXp": 20,
      "rewardTalents": 5,
      "progress": 0,
      "completed": false
    }
  ]
}
```

### 6.2 Join Challenge

Status: `PLANNED`

```http
POST /api/v1/challenges/{challengeId}/join
```

Response `201`

### 6.3 Update Challenge Progress

Status: `PLANNED`

```http
PATCH /api/v1/challenges/{challengeId}/progress
```

Request:

```json
{
  "progress": 1
}
```

### 6.4 Claim Reward

Status: `PLANNED`

```http
POST /api/v1/challenges/{challengeId}/claim-reward
Idempotency-Key: uuid
```

Response `200`:

```json
{
  "xp": 20,
  "talents": 5,
  "claimedAt": "2026-08-03T00:00:00Z"
}
```

### 6.5 Quiz

Status: `PLANNED`

```http
GET  /api/v1/challenges/{challengeId}/quiz
POST /api/v1/challenges/{challengeId}/quiz/submit
```

Submit request:

```json
{
  "answers": [
    {
      "questionId": "uuid",
      "choiceId": "uuid"
    }
  ]
}
```

## 7. Pasture API

### 7.1 Create Pasture

Status: `PLANNED`

```http
POST /api/v1/pastures
```

Request:

```json
{
  "name": "청년 목장"
}
```

Response `201`:

```json
{
  "id": "uuid",
  "name": "청년 목장",
  "inviteCode": "ABCD1234",
  "ownerUserId": "uuid"
}
```

### 7.2 My Pastures

Status: `PLANNED`

```http
GET /api/v1/pastures/my
```

### 7.3 Join by Invite Code

Status: `PLANNED`

```http
POST /api/v1/pastures/join
```

Request:

```json
{
  "inviteCode": "ABCD1234"
}
```

### 7.4 Pasture Detail

Status: `PLANNED`

```http
GET /api/v1/pastures/{pastureId}
```

### 7.5 Pasture Notices

Status: `PLANNED`

```http
GET    /api/v1/pastures/{pastureId}/notices
POST   /api/v1/pastures/{pastureId}/notices
PATCH  /api/v1/pastures/{pastureId}/notices/{noticeId}
DELETE /api/v1/pastures/{pastureId}/notices/{noticeId}
```

### 7.6 Pasture Reading Status

Status: `PLANNED`

```http
GET /api/v1/pastures/{pastureId}/reading-status
```

### 7.7 Attendance

Status: `PLANNED`

```http
POST /api/v1/pastures/{pastureId}/attendance
GET  /api/v1/pastures/{pastureId}/attendance?date=2026-08-03
```

### 7.8 Member Management

Status: `PLANNED`

```http
GET    /api/v1/pastures/{pastureId}/members
PATCH  /api/v1/pastures/{pastureId}/members/{memberId}/role
DELETE /api/v1/pastures/{pastureId}/members/{memberId}
```

### 7.9 큐티 모임 QR

Status: IMPLEMENTED

QT 세션은 고정 목장 소속 `PASTURE`와 목장 없이 생성자에게 귀속되는 `STANDALONE` 범위로 구분합니다. 두 생성 API 모두 JWT가 필요하며 요청에는 title, passageId, meetingStartsAt, meetingEndsAt, joinStartsAt을 전달할 수 있습니다.

- GET /api/v1/quiet-time/pastures/mine
- POST /api/v1/quiet-time/pastures/{pastureId}/sessions
- POST /api/v1/quiet-time/sessions/standalone

응답에는 `scope`, nullable `pasture`, `hostDisplayName`, `offlineCode`, `joinCode`, `inviteUrl`, `joinStartsAt`, `expiresAt`, `qrStatus`가 포함됩니다. `offlineCode`는 QR 링크용 10자리 토큰이고 `joinCode`는 사람이 입력하는 6자리 코드입니다. `expiresAt`은 기본적으로 모임 종료 30분 후이며 QR 이미지는 프론트에서 `inviteUrl`을 기반으로 생성합니다.

- GET /api/v1/quiet-time/offline/{offlineCode}
- POST /api/v1/quiet-time/join-codes/resolve
- POST /api/v1/quiet-time/offline/{offlineCode}/join
- POST /api/v1/quiet-time/sessions/{sessionId}/qr/reissue
- DELETE /api/v1/quiet-time/sessions/{sessionId}/qr
- POST /api/v1/quiet-time/sessions/{sessionId}/close

QR 상태는 SCHEDULED, OPEN, EXPIRED, REVOKED, CLOSED입니다.

`POST /api/v1/quiet-time/join-codes/resolve`는 JWT가 필요하며 `{"joinCode":"ABC234"}`를 받아 기존 `QuietTimeSessionResponse`를 반환합니다. 서버는 앞뒤 공백과 소문자를 정규화하고 사용자별 Redis rate limit으로 코드 추측을 제한합니다.

상세 정책: [큐티 모임 QR 만료 및 폐기 정책](quiet-time-qr-policy.ko.md)

범위와 소유권 정책: [고정 목장 및 일회성 QT 정책](quiet-time-session-scope-policy.ko.md)

### 7.10 큐티 질문별 실시간 답변

Status: IMPLEMENTED

모임 리더 또는 QR 참여가 완료된 목원만 접근할 수 있습니다. 질문 문구는 클라이언트가 보내지 않고 서버가 세션 passage의 질문 유형과 0부터 시작하는 질문 번호로 확정합니다. 같은 사용자가 같은 질문에 다시 저장하면 새 행을 추가하지 않고 최신 답변을 갱신합니다.

```http
PUT /api/v1/quiet-time/sessions/{sessionId}/answers/{category}/{questionIndex}
GET /api/v1/quiet-time/sessions/{sessionId}/answers
GET /api/v1/quiet-time/sessions/{sessionId}/answers/stream
```

`category`는 `OBSERVATION`, `MEDITATION`, `SHARING` 중 하나이며 답변 본문은 1자 이상 2,000자 이하입니다. 저장 응답과 `answer-updated` SSE 이벤트는 다음 형식을 사용합니다.

```json
{
  "id": "answer-uuid",
  "sessionId": "session-uuid",
  "userId": "user-uuid",
  "authorDisplayName": "목원 한나",
  "questionCategory": "MEDITATION",
  "questionIndex": 0,
  "questionText": "오늘 내 삶에 적용할 말씀은 무엇인가요?",
  "content": "염려를 기도로 맡기겠습니다.",
  "createdAt": "2026-08-22T01:00:00Z",
  "updatedAt": "2026-08-22T01:05:00Z"
}
```

SSE는 연결 중 변경을 빠르게 전달하는 채널이며 영속 메시지 큐가 아닙니다. 모바일은 최초 연결과 재연결 때 `GET .../answers` 스냅샷을 다시 조회해 연결 중 유실 가능성을 복구합니다. 운영 환경의 여러 백엔드 인스턴스는 Redis Pub/Sub으로 이벤트를 팬아웃하고, DB 커밋이 완료된 답변만 발행합니다.


## 8. Prayer API

### 8.1 Prayer CRUD

Status: `PLANNED`

```http
POST   /api/v1/prayers
GET    /api/v1/prayers
GET    /api/v1/prayers/{prayerId}
PATCH  /api/v1/prayers/{prayerId}
DELETE /api/v1/prayers/{prayerId}
```

Create request:

```json
{
  "title": "가족을 위한 기도",
  "content": "...",
  "visibility": "PRIVATE"
}
```

Visibility values:

```text
PRIVATE, PASTURE
```

### 8.2 Mark Answered

Status: `PLANNED`

```http
POST /api/v1/prayers/{prayerId}/answered
```

Request:

```json
{
  "answeredAt": "2026-08-03T00:00:00Z",
  "note": "응답 기록"
}
```

### 8.3 Pasture Shared Prayers

Status: `PLANNED`

```http
GET /api/v1/pastures/{pastureId}/prayers
POST /api/v1/pastures/{pastureId}/prayers/{prayerId}/comments
GET /api/v1/pastures/{pastureId}/prayers/{prayerId}/comments
```

## 9. Journal API

### 9.1 Bible Journal CRUD

Status: `PLANNED`

```http
POST   /api/v1/journals
GET    /api/v1/journals
GET    /api/v1/journals/{journalId}
PATCH  /api/v1/journals/{journalId}
DELETE /api/v1/journals/{journalId}
```

Create request:

```json
{
  "content": "오늘 말씀을 읽고 느낀 점...",
  "verseIds": [1, 2, 3]
}
```

### 9.2 AI Review Journal

Status: `PLANNED`

```http
POST /api/v1/journals/{journalId}/ai-review
```

Response `200`:

```json
{
  "aiReview": "...",
  "emotion": "PEACEFUL",
  "recommendedVerses": [],
  "meditationQuestions": []
}
```

## 10. Verse Card API

### 10.1 Create Verse Card

Status: `PLANNED`

```http
POST /api/v1/verse-cards
```

Request:

```json
{
  "verseId": 1,
  "backgroundId": "forest-01",
  "templateId": "simple-centered",
  "textColor": "#FFFFFF"
}
```

Response `201`:

```json
{
  "id": "uuid",
  "imageUrl": "https://r2.example.com/cards/card.png",
  "shareUrl": "https://mypasture.app/cards/uuid"
}
```

### 10.2 Backgrounds

Status: `PLANNED`

```http
GET /api/v1/verse-cards/backgrounds
```

## 11. Talent/Reward API

### 11.1 Talent Balance

Status: `PLANNED`

```http
GET /api/v1/talents/balance
```

Response:

```json
{
  "balance": 320
}
```

### 11.2 Talent History

Status: `PLANNED`

```http
GET /api/v1/talents/history
```

### 11.3 Reward Shop

Status: `PLANNED`

```http
GET /api/v1/rewards
```

### 11.4 Exchange Reward

Status: `PLANNED`

```http
POST /api/v1/rewards/{rewardId}/exchanges
Idempotency-Key: uuid
```

Request:

```json
{
  "quantity": 1
}
```

Response `201`:

```json
{
  "exchangeId": "uuid",
  "status": "REQUESTED",
  "spentTalents": 100
}
```

## 12. Notification API

### 12.1 Register FCM Token

Status: `IMPLEMENTED`

```http
POST /api/v1/notifications/devices
```

Request:

```json
{
  "fcmToken": "fcm-token",
  "platform": "IOS",
  "deviceId": "device-uuid"
}
```

Response `204`

### 12.2 Delete FCM Token

Status: `IMPLEMENTED`

```http
DELETE /api/v1/notifications/devices/{deviceId}
```

기기 행과 발송 감사 이력은 유지하고 활성 상태만 해제합니다. 응답은 `204 No Content`입니다.

### 12.3 Daily Word Notification Setting

Status: `IMPLEMENTED`

```http
GET /api/v1/notifications/settings/daily-word
PUT /api/v1/notifications/settings/daily-word
```

```json
{
  "enabled": true,
  "notificationTime": "07:00:00",
  "timezone": "Asia/Seoul"
}
```

### 12.4 Notification Center

Status: `IMPLEMENTED`

```http
GET  /api/v1/notifications?cursor={cursor}&limit=20
GET  /api/v1/notifications/unread-count
POST /api/v1/notifications/{notificationId}/read
POST /api/v1/notifications/read-all
```

목록은 `(publishedAt, id)` 기반 opaque cursor를 사용합니다. `limit`은 1~50이며 계정 생성 이후 게시되고 아직 만료되지 않은 알림만 반환합니다.

Response `200`:

```json
{
  "items": [
    {
      "id": "notification-uuid",
      "type": "DAILY_WORD",
      "title": "오늘의 말씀",
      "body": "시편 23편 1절",
      "destination": {
        "type": "TODAY_WORD",
        "dailyWordId": "daily-word-uuid",
        "date": "2026-08-24",
        "version": "KOR1910",
        "bookCode": "PSA",
        "bookName": "시편",
        "chapter": 23,
        "verse": 1
      },
      "read": false,
      "publishedAt": "2026-08-24T00:00:00Z"
    }
  ],
  "nextCursor": null,
  "hasNext": false
}
```

서버는 임의 URL을 반환하지 않고 `destination.type` allow-list만 제공합니다. 미읽음 API와 전체 읽음 응답은 `{ "unreadCount": 0 }` 형식입니다. 존재하지 않거나 다른 사용자가 볼 수 없는 알림은 `404 NOTIFICATION_NOT_FOUND`, 잘못된 cursor는 `400 INVALID_NOTIFICATION_REQUEST`를 반환합니다.

## 13. File API

### 13.1 Create Upload URL

Status: `PLANNED`

Cloudflare R2 presigned upload URL을 발급합니다.

```http
POST /api/v1/files/upload-url
```

Request:

```json
{
  "fileName": "profile.png",
  "contentType": "image/png",
  "purpose": "PROFILE_IMAGE"
}
```

Response `200`:

```json
{
  "uploadUrl": "https://...",
  "fileUrl": "https://cdn.mypasture.app/profile.png",
  "expiresIn": 600
}
```

## 14. Admin API

Admin APIs require `ADMIN` role.

### 14.1 Users

Status: `PLANNED`

```http
GET   /api/v1/admin/users
GET   /api/v1/admin/users/{userId}
PATCH /api/v1/admin/users/{userId}
POST  /api/v1/admin/users/{userId}/block
POST  /api/v1/admin/users/{userId}/unblock
```

### 14.2 Today Word Management

Status: `IMPLEMENTED`

```http
GET  /api/v1/admin/daily-words?from={date}&to={date}&status={status}
GET  /api/v1/admin/daily-words/generation-jobs
GET  /api/v1/admin/daily-words/operations-summary?from={date}&to={date}
GET  /api/v1/admin/daily-words/incidents?status={status}
GET  /api/v1/admin/daily-words/incident-email-deliveries?status={status}&incidentId={uuid}&from={instant}&to={instant}&page={page}&size={size}
POST /api/v1/admin/daily-words/{date}/generation-jobs
POST /api/v1/admin/daily-words/{date}/manual-drafts
PUT  /api/v1/admin/daily-words/{dailyWordId}
POST /api/v1/admin/daily-words/{dailyWordId}/publish
POST /api/v1/admin/daily-words/{dailyWordId}/reject
POST /api/v1/admin/daily-words/incidents/{incidentId}/acknowledge
POST /api/v1/admin/daily-words/incidents/{incidentId}/resolve
POST /api/v1/admin/daily-words/incident-email-deliveries/{deliveryId}/requeue
```

모든 변경 요청은 `operationId`와 5자 이상의 `reason`을 받습니다. 생성 작업은 날짜별 활성 작업 유일 제약과 lease로 중복 실행을 막고, 게시 시 `verse_id`와 성경 원문을 다시 검증합니다. 수동 초안은 실제 DB 성경 절만 선택하며 활성 AI 작업을 취소하고 늦은 AI 결과의 덮어쓰기를 거부합니다.

운영 요약은 최대 93일 범위에서 오늘·내일 게시 준비 상태, 콘텐츠 및 생성 작업 상태, 게시가 필요한 날짜, AI 토큰과 예상 비용 합계를 반환합니다. 생성 작업은 행 전체가 아니라 DB 상태 집계로 계산합니다.

게시 누락, 생성 최종 실패, 생성 지연은 날짜·유형별 운영 사건으로 자동 감지됩니다. 같은 심각도의 반복 감지는 이메일 outbox를 중복 생성하지 않고, 재발 또는 심각도 상승 때만 새 알림 버전을 예약합니다.

이메일 outbox 조회는 기본 7일, 최대 31일과 페이지 크기 최대 100으로 제한합니다. 수신자는 마스킹하고 외부 오류의 이메일·인증 문자열을 제거합니다. `DEAD`, `RETRY_WAIT` delivery만 수동 재처리할 수 있으며 `operationId`와 5~500자 사유를 감사 로그에 저장합니다.

### 14.3 Notification Operations

Status: `IMPLEMENTED`

```http
GET /api/v1/admin/notifications/operations?type=DAILY_WORD&from={instant}&to={instant}&page=0&size=20
```

기본 조회 기간은 7일, 최대 31일이고 페이지 크기는 1~100입니다. 공통 알림 수, 기기별 작업 수, `PENDING`, `PROCESSING`, `RETRY_WAIT`, `SENT`, `DEAD`와 인앱 열람 수를 반환합니다. 여기서 `SENT`는 FCM provider가 요청을 접수한 상태이며 실제 기기 도착을 의미하지 않습니다. 사용자 식별자, FCM token과 외부 오류 원문은 반환하지 않습니다.

### 14.4 Challenge Management

Status: `PLANNED`

```http
GET    /api/v1/admin/challenges
POST   /api/v1/admin/challenges
PATCH  /api/v1/admin/challenges/{challengeId}
DELETE /api/v1/admin/challenges/{challengeId}
```

### 14.5 Reward Management

Status: `PLANNED`

```http
GET    /api/v1/admin/rewards
POST   /api/v1/admin/rewards
PATCH  /api/v1/admin/rewards/{rewardId}
DELETE /api/v1/admin/rewards/{rewardId}
GET    /api/v1/admin/reward-exchanges
PATCH  /api/v1/admin/reward-exchanges/{exchangeId}
```

### 14.5 Prayer Moderation

Status: `PLANNED`

```http
GET  /api/v1/admin/prayers
POST /api/v1/admin/prayers/{prayerId}/hide
POST /api/v1/admin/prayers/{prayerId}/restore
```

### 14.6 Push Campaigns

Status: `PLANNED`

```http
POST /api/v1/admin/push-campaigns
GET  /api/v1/admin/push-campaigns
GET  /api/v1/admin/push-campaigns/{campaignId}
```

Create request:

```json
{
  "title": "오늘의 말씀",
  "body": "오늘 묵상 말씀을 확인해보세요.",
  "target": {
    "type": "ALL"
  },
  "scheduledAt": "2026-08-03T07:00:00Z"
}
```

### 14.7 AI Usage Stats

Status: `PLANNED`

```http
GET /api/v1/admin/ai-usage?from=2026-08-01&to=2026-08-31
```

Response `200`:

```json
{
  "totalRequests": 12000,
  "promptTokens": 1400000,
  "completionTokens": 3200000,
  "estimatedCostUsd": 7.25,
  "cacheHitRate": 0.42
}
```

### 14.8 Service Stats

Status: `PLANNED`

```http
GET /api/v1/admin/stats/overview
```

Response `200`:

```json
{
  "users": {
    "total": 100000,
    "dailyActive": 24000,
    "premium": 3500
  },
  "bible": {
    "chaptersReadToday": 88000
  },
  "ai": {
    "requestsToday": 12000,
    "estimatedCostUsdToday": 7.25
  }
}
```

### 14.9 Quiet Time Operations

Status: `IMPLEMENTED`

```http
GET  /api/v1/admin/quiet-time/sessions?scope=STANDALONE&status=OPEN&page=0&size=20
GET  /api/v1/admin/quiet-time/sessions/{sessionId}
GET  /api/v1/admin/quiet-time/statistics?from=2026-08-01&to=2026-08-31
POST /api/v1/admin/quiet-time/sessions/{sessionId}/close
POST /api/v1/admin/quiet-time/sessions/{sessionId}/revoke-qr
```

관리자 조치 요청은 재시도 시 중복 기록을 막는 `operationId`와 5~500자의 사유가 필요합니다.

```json
{
  "operationId": "9fc8ef06-1441-4c8d-a0bf-07d1421b9510",
  "reason": "노출된 QR 초대 링크 즉시 폐기"
}
```

목록과 통계는 사용자의 QT 답변·묵상 원문을 반환하지 않습니다. 상세 응답에는 세션 운영 정보와 최근 관리자 감사 로그만 포함됩니다.

### 14.10 AI Runtime Model Operations

Status: `IMPLEMENTED`

```http
GET  /api/v1/admin/ai/runtime-config?useCase=AI_PASTOR
GET  /api/v1/admin/ai/runtime-config?useCase=DAILY_WORD
POST /api/v1/admin/ai/runtime-config/activate
POST /api/v1/admin/ai/runtime-config/rollback
```

조회와 이력은 `AI_PASTOR`, `DAILY_WORD` use case별로 분리됩니다. 활성화 요청은 서버 허용 목록에 있는 생성·평가 모델만 사용할 수 있으며, `operationId`와 5~500자의 변경 사유가 필요합니다.

```json
{
  "operationId": "9fc8ef06-1441-4c8d-a0bf-07d1421b9510",
  "useCase": "AI_PASTOR",
  "generatorModel": "gpt-5.6-terra",
  "evaluatorModel": "gpt-5.6-luna",
  "generatorReasoningEffort": "LOW",
  "evaluatorReasoningEffort": "MEDIUM",
  "maxOutputTokens": 700,
  "evaluationMaxOutputTokens": 250,
  "evaluationEnabled": true,
  "evaluationThreshold": 4,
  "maxRevisionCount": 1,
  "reason": "품질 평가 결과와 비용 지표를 반영한 운영 설정"
}
```

롤백은 과거 행을 수정하지 않고 대상 설정을 복제한 새 버전을 생성합니다. 모든 변경은 관리자 ID, 사유, 요청 지문과 함께 감사 로그에 남습니다.

## 15. Policy and Consent API

Status: `IMPLEMENTED`

```http
GET  /api/v1/policies/current?scope=ACCOUNT
GET  /api/v1/policies/{type}/{version}
GET  /api/v1/policies/consent-status?scopes=ACCOUNT,AI
POST /api/v1/policies/consents
```

정책 원문과 현재 버전은 공개 조회할 수 있습니다. 동의 상태와 변경은 Bearer JWT가 필요합니다. `REQUIRED` 문서는 현재 버전에 동의해야 해당 기능을 이용할 수 있고, `OPTIONAL` 문서는 설정에서 철회할 수 있습니다. 현재 상태는 `user_policy_consents`, 변경 이력은 추가 전용 `user_policy_consent_events`에 저장합니다.

## 16. Moderation and Event Reward API

### 16.1 사용자 신고·차단

Status: `IMPLEMENTED`

```http
POST   /api/v1/moderation/reports
GET    /api/v1/moderation/blocks
PUT    /api/v1/moderation/blocks/{blockedUserId}
DELETE /api/v1/moderation/blocks/{blockedUserId}
```

신고 대상은 현재 `AI_ANSWER`, `QUIET_TIME_ANSWER`입니다. 서버는 AI 답변 소유권 또는 QT 참여 권한을 검증하고, 같은 사용자의 같은 대상 신고는 기존 행을 다시 `OPEN` 처리합니다. QT 작성자 차단은 개인 조회와 SSE 전달에 즉시 적용됩니다.

```json
{
  "targetType": "QUIET_TIME_ANSWER",
  "targetId": "f922ce9c-e54a-4279-82b2-9ac7ebc4089e",
  "reason": "HARASSMENT",
  "details": "다른 참여자를 공격하는 표현입니다."
}
```

### 16.2 관리자 신고 검토

Status: `IMPLEMENTED`

```http
GET   /api/v1/admin/moderation/reports?status=OPEN&targetType=QUIET_TIME_ANSWER&page=0&size=20
PATCH /api/v1/admin/moderation/reports/{reportId}
```

`CONTENT_HIDDEN` 조치는 해당 QT 답변을 모든 조회와 실시간 전달에서 숨깁니다. 관리자 요청은 `operationId`와 요청 지문으로 멱등 처리하며, 다른 요청에서 같은 ID를 재사용하면 `409 MODERATION_OPERATION_CONFLICT`를 반환합니다.

### 16.3 무료 이벤트 꾸미기 보상

Status: `IMPLEMENTED`

```http
POST /api/v1/coupons/redeem
GET  /api/v1/coupons/rewards
GET  /api/v1/admin/coupon-campaigns?page=0&size=20
POST /api/v1/admin/coupon-campaigns
POST /api/v1/admin/coupon-campaigns/{campaignId}/deactivate
```

자체 코드는 `PASTURE_DECORATION_ITEM`만 지급하며 구독 등급, AI 한도, 유료 기능을 변경하지 않습니다.

### 16.4 소셜 제공자 준비 상태

Status: `IMPLEMENTED`

```http
GET /api/v1/auth/social-login/providers
```

비인증 공개 응답은 서버에 검증 설정이 존재하는 제공자만 `true`로 반환하며 비밀값이나 Client ID 원문은 노출하지 않습니다. 모바일은 로컬 네이티브 설정과 이 응답을 함께 확인해 실패가 확정된 버튼을 숨깁니다.

## 17. Implementation Status Summary

| Domain | API status |
|---|---|
| Auth | signup implemented, login partial, OAuth/JWT planned |
| User/Profile | planned |
| Bible | 66권 DB 조회·검색과 pgvector RAG index 구현 |
| AI Assistant | OpenAI Responses/Embeddings, RAG 인용 검증, 생성·평가 모델과 최대 1회 수정 구현 |
| Today Word | RAG 생성 작업, 원문 검증, 관리자 검수·게시와 공개 조회 구현 |
| Challenge | planned |
| Pasture | planned |
| Prayer | planned |
| Journal | planned |
| Verse Card | planned |
| Talent/Reward | planned |
| Notification | 기기별 delivery, lease 복구, 재시도와 게시된 오늘의 말씀 발송 구현 |
| File/R2 | planned |
| Admin | 인증, 성경 해설·embedding, QT 운영, AI 모델 및 오늘의 말씀 검수·감사 로그 구현 |

## 18. Next API Implementation Order

추천 구현 순서:

1. Auth JWT/refresh token: 운영용 인증 기반 완성
2. User profile: `GET /users/me`, `PATCH /users/me`
3. Bible read/search/progress/bookmark/highlight
4. AI chat streaming and OpenAI real adapter
5. Today word and push settings
6. Prayer and pasture MVP
7. Challenge, XP, talent ledger
8. Admin dashboard APIs
