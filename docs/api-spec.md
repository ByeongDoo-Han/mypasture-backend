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
  "verificationToken": "one-time-signup-token"
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

Errors: `400 EMAIL_VERIFICATION_REQUIRED`, `409 EMAIL_ALREADY_REGISTERED`.

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

### 1.10 Delete account

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

탈퇴 시 로그인 제공자 연결과 활성 재설정 토큰을 폐기하고 사용자 개인정보를 익명화한다. 기존 JWT는 즉시 무효화되며 동일 이메일로 재가입할 수 있다.

Errors:

| HTTP | code |
|---:|---|
| 400 | `INVALID_DELETION_CONFIRMATION` |
| 401 | `REAUTHENTICATION_FAILED` |
| 401 | `UNAUTHORIZED` |

### 1.11 Refresh token / logout

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

Status: `PLANNED`

```http
POST /api/v1/bible-bookmarks
DELETE /api/v1/bible-bookmarks/{verseId}
GET /api/v1/bible-bookmarks
```

Request:

```json
{
  "verseId": 1
}
```

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

Request:

```json
{
  "question": "불안합니다. 어떤 말씀을 묵상하면 좋을까요?"
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
  "cacheHit": false
}
```

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

event: citations
data: [{"verseId":1001,"book":"시편","chapter":23,"verse":1}]

event: usage
data: {"promptTokens":123,"completionTokens":456,"embeddingTokens":18,"estimatedCostUsd":0.000123,"retrievalStrategy":"PGVECTOR","questionIntent":"PASTORAL_COUNSELING","cacheHit":false}

event: done
data: {"status":"completed"}
```

### 4.4 AI Conversations

Status: `PLANNED`

```http
GET    /api/v1/ai/conversations
POST   /api/v1/ai/conversations
GET    /api/v1/ai/conversations/{conversationId}
DELETE /api/v1/ai/conversations/{conversationId}
```

### 4.5 Daily Meditation AI Batch

Status: `PLANNED`

관리자 또는 batch worker가 하루 1회 생성합니다. 모든 사용자는 DB에 저장된 같은 결과를 조회합니다.

```http
POST /api/v1/admin/daily-words/generate
```

## 5. Today's Word API

### 5.1 Get Today's Word

Status: `PLANNED`

```http
GET /api/v1/today-word?date=2026-08-03
```

Response `200`:

```json
{
  "date": "2026-08-03",
  "verse": {
    "verseId": 1,
    "book": "시편",
    "chapter": 23,
    "verse": 1,
    "text": "..."
  },
  "meditation": "오늘의 묵상",
  "practiceQuestions": [
    "오늘 이 말씀을 어디에 적용할 수 있나요?"
  ]
}
```

### 5.2 Today Word Notification Settings

Status: `PLANNED`

```http
GET /api/v1/today-word/notification-settings
PUT /api/v1/today-word/notification-settings
```

Request:

```json
{
  "enabled": true,
  "times": ["07:00", "21:00"],
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

세션 생성은 POST /api/v1/quiet-time/pastures/{pastureId}/sessions를 사용하며 JWT가 필요합니다. 요청에는 title, passageId, meetingStartsAt, meetingEndsAt, joinStartsAt을 전달할 수 있습니다.

응답에는 offlineCode, inviteUrl, joinStartsAt, expiresAt, qrStatus가 포함됩니다. expiresAt은 기본적으로 모임 종료 30분 후이며 QR 이미지는 프론트에서 inviteUrl을 기반으로 생성합니다.

- GET /api/v1/quiet-time/offline/{offlineCode}
- POST /api/v1/quiet-time/offline/{offlineCode}/join
- POST /api/v1/quiet-time/sessions/{sessionId}/qr/reissue
- DELETE /api/v1/quiet-time/sessions/{sessionId}/qr
- POST /api/v1/quiet-time/sessions/{sessionId}/close

QR 상태는 SCHEDULED, OPEN, EXPIRED, REVOKED, CLOSED입니다.

상세 정책: [큐티 모임 QR 만료 및 폐기 정책](quiet-time-qr-policy.ko.md)


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

Status: `PLANNED`

```http
POST /api/v1/notifications/fcm-tokens
```

Request:

```json
{
  "token": "fcm-token",
  "platform": "IOS",
  "deviceId": "device-uuid"
}
```

Response `204`

### 12.2 Delete FCM Token

Status: `PLANNED`

```http
DELETE /api/v1/notifications/fcm-tokens/{deviceId}
```

### 12.3 Notification Center

Status: `PLANNED`

```http
GET /api/v1/notifications
PATCH /api/v1/notifications/{notificationId}/read
PATCH /api/v1/notifications/read-all
```

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

Status: `PLANNED`

```http
GET    /api/v1/admin/today-words
POST   /api/v1/admin/today-words
PATCH  /api/v1/admin/today-words/{todayWordId}
DELETE /api/v1/admin/today-words/{todayWordId}
POST   /api/v1/admin/today-words/generate
```

### 14.3 Challenge Management

Status: `PLANNED`

```http
GET    /api/v1/admin/challenges
POST   /api/v1/admin/challenges
PATCH  /api/v1/admin/challenges/{challengeId}
DELETE /api/v1/admin/challenges/{challengeId}
```

### 14.4 Reward Management

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

## 15. Implementation Status Summary

| Domain | API status |
|---|---|
| Auth | signup implemented, login partial, OAuth/JWT planned |
| User/Profile | planned |
| Bible | 66권 DB 조회·검색과 pgvector RAG index 구현 |
| AI Assistant | OpenAI Responses/Embeddings, citation 검증 구현; upstream streaming 보완 필요 |
| Today Word | planned |
| Challenge | planned |
| Pasture | planned |
| Prayer | planned |
| Journal | planned |
| Verse Card | planned |
| Talent/Reward | planned |
| Notification | planned |
| File/R2 | planned |
| Admin | planned |

## 16. Next API Implementation Order

추천 구현 순서:

1. Auth JWT/refresh token: 운영용 인증 기반 완성
2. User profile: `GET /users/me`, `PATCH /users/me`
3. Bible read/search/progress/bookmark/highlight
4. AI chat streaming and OpenAI real adapter
5. Today word and push settings
6. Prayer and pasture MVP
7. Challenge, XP, talent ledger
8. Admin dashboard APIs
