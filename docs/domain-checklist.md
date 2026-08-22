# My Pasture Domain Checklist

이 문서는 백엔드 패키지를 도메인 우선 구조로 재배치한 뒤, 각 도메인에서 구현된 것과 다음에 구현할 것을 빠르게 확인하기 위한 체크리스트입니다.

## Package Rule

각 기능은 먼저 도메인으로 나누고, 도메인 내부에서 Clean Architecture 레이어를 나눕니다.

```text
com.example.mypasture
├── ai
│   ├── presentation
│   ├── application
│   ├── domain
│   └── infrastructure
├── auth
│   ├── presentation
│   ├── application
│   ├── domain
│   └── infrastructure
└── user
    ├── presentation
    ├── application
    ├── domain
    └── infrastructure
```

레이어 의존 방향은 아래 규칙을 지킵니다.

```text
presentation -> application -> domain
infrastructure -> application/domain
application/domain -> infrastructure 의존 금지
```

## AI Domain

Package: `com.example.mypasture.ai`

구현됨:

- [x] `presentation/AiApi`, `AiController`: 일반 응답과 실시간 SSE 요청 진입점
- [x] `application/AskBibleAssistantUseCase`: 입력 길이 제한, 무료 사용자 일일 quota, cache, RAG, completion, usage log 순서 보장
- [x] `application/AiPorts`: Bible retrieval, text embedding, embedding store, AI completion/cache/usage port 정의
- [x] `domain/AiModels`: `BibleCitation`, `GroundedAnswer`, usage policy 모델
- [x] `infrastructure/JdbcBibleRetrievalAdapter`: pgvector cosine Top-K와 실제 성경 원문 재검증
- [x] `infrastructure/OpenAiCompletionAdapter`: OpenAI Responses API와 citation 후검증
- [x] `infrastructure/OpenAiTextEmbeddingAdapter`: 질문/성경 본문 embedding batch, retry, timeout, 비용 집계
- [x] `application/BibleEmbeddingIndexService`: 본문 hash 기반 멱등 backfill과 다중 서버 advisory lock
- [x] `infrastructure/RedisAiCacheAdapter`: 동일 질문 Redis 캐시
- [x] `infrastructure/JdbcAiUsageAdapter`: AI 사용량/토큰/비용 기록

구현 필요:

- [x] OpenAI Responses API 실제 연동
- [x] OpenAI Responses API delta 기반 실시간 SSE와 bounded executor 적용
- [~] Retry, timeout 적용 완료, 분산 rate limit filter 필요
- [x] pgvector embedding 검색과 로컬 lexical fallback 적용
- [ ] 유사 질문 semantic cache 구현
- [ ] conversation/session 저장
- [x] AI 답변 안전 정책: 검색되지 않은 verse ID와 장·절을 SSE 전송 전 차단
- [x] AI 예외를 표준 API error response로 변환하는 exception handler
- [~] 단위 테스트: RAG 결과 없음과 index batch 완료, quota/cache 추가 필요
- [~] 통합 테스트: AI usage log 완료, 실제 Redis 장애 테스트 필요

운영 문서: [AI 목자 pgvector RAG 운영 가이드](ai-rag-operations.ko.md)

## Quiet Time QR Domain

Package: com.example.mypasture.quiet_time

구현됨:

- [x] 큐티 목장과 리더 사용자 소유권
- [x] 큐티 세션과 QR 발급 이력 분리
- [x] 모임 종료 후 30분 자동 만료
- [x] 참여 시작 시간과 서버 시간 기준 상태 판정
- [x] QR 재발급 시 기존 활성 코드 즉시 폐기
- [x] 리더의 QR 즉시 폐기와 세션 종료
- [x] 만료·폐기 QR의 정보 조회 허용, 참여 차단
- [x] 서버 inviteUrl 발급과 모바일 QR 이미지 렌더링 책임 분리
- [x] 세션/QR 행 잠금과 기기별 중복 참여 방지
- [x] QR 관리 API JWT 인증과 리더 소유권 검사
- [x] 시작 전·만료·폐기·종료·권한 오류 코드
- [x] QR 정책 통합 테스트
- [x] 관찰·묵상·나눔 질문별 참여자 최신 답변 저장과 수정
- [x] 참여자·리더 전용 답변 스냅샷 조회 및 외부 사용자 차단
- [x] SSE 실시간 답변 전파와 재연결용 REST 스냅샷
- [x] 다중 서버 Redis Pub/Sub 팬아웃과 단일 서버 로컬 대체 구현
- [x] 최초 답변 동시 생성 방지를 위한 세션 행 잠금과 유일 키
- [x] 질문별 답변 권한·갱신·SSE 통합 테스트
- [x] 고정 목장 `PASTURE`와 목장 없는 `STANDALONE` 세션 범위 분리
- [x] 일회성 세션 생성자 소유권과 nullable 목장 출석
- [x] 로그인 리더의 고정 목장 목록 조회

구현 필요:

- [ ] 목장 장기 초대 QR과 당일 참석 QR 타입 분리
- [ ] 참여 정원과 대기 명단
- [ ] Redis/IP/기기 기반 참여 rate limit
- [ ] 민감한 모임의 리더 승인 또는 추가 PIN
- [x] 모바일 Universal Link/App Link 라우팅 설정
- [ ] mypasture.app 설치 안내 landing page
- [ ] AASA와 assetlinks.json 도메인 배포 및 앱 서명값 연결
- [ ] 만료 QR 이력 정리 및 감사 보존기간
- [ ] QR 발급·폐기 감사 이벤트
- [ ] 기존 leader 없는 목장 데이터 이관
- [ ] 답변 삭제·수정 이력과 리더 신고/숨김 정책
- [ ] 장시간 모임의 SSE 동시 연결 수 모니터링과 부하 테스트
- [ ] 고정 목장 membership과 리더·부리더 역할
- [ ] 일회성 참가자의 동의 기반 고정 목장 전환

정책 문서: [큐티 모임 QR 만료 및 폐기 정책](quiet-time-qr-policy.ko.md)

범위 정책: [고정 목장 및 일회성 QT 정책](quiet-time-session-scope-policy.ko.md)


## Auth Domain

Package: `com.example.mypasture.auth`

구현됨:

- [x] Email/password signup and login
- [x] Google/Apple/Kakao social login token verification
- [x] JWT access token 발급 및 Spring Security authentication filter
- [x] Current user 조회: `GET /api/v1/auth/me`
- [x] 비밀번호 재설정 요청/확정
- [x] 재설정 토큰 SHA-256 저장, 30분 만료, 1회 사용, 재요청 cooldown
- [x] 비밀번호 변경 시 기존 JWT 전체 무효화
- [x] 회원탈퇴 시 현재 비밀번호 재확인, 로그인 수단 제거, 개인정보 익명화
- [x] 탈퇴 즉시 기존 JWT 무효화 및 동일 이메일 재가입
- [x] Auth API interface와 Controller 구현 분리
- [x] 인증 API 통합 테스트

구현 필요:

- [ ] Refresh token 저장, 회전, 재사용 탐지, 폐기
- [ ] 이메일 인증과 가입 완료 정책
- [ ] Redis/IP 기반 로그인 및 비밀번호 재설정 rate limit
- [ ] 메일 outbox, retry, 실패 모니터링
- [ ] 소셜 전용 계정 탈퇴 시 provider recent reauthentication
- [ ] 비밀번호 유출 목록 검사와 정책 고도화
- [ ] 탈퇴 데이터별 삭제/익명화/법정 보존 정책 확정
- [ ] 구독 취소 및 App Store/Google Play server notification 연계
- [ ] 관리자 계정 차단, 휴면, 강제 세션 만료
- [ ] 보안 감사 이벤트와 이상 로그인 탐지

권장 파일 위치:

```text
auth/presentation/AuthApi.kt
auth/presentation/AuthController.kt
auth/application/AuthService.kt
auth/application/PasswordResetNotificationSender.kt
auth/domain/AuthAccountEntity.kt
auth/domain/PasswordResetTokenEntity.kt
auth/infrastructure/JwtTokenProvider.kt
auth/infrastructure/PasswordResetNotificationAdapters.kt
```

## User Domain

Package: `com.example.mypasture.user`

구현됨:

- [x] `domain/UserEntity`: 사용자 JPA entity
- [x] `domain/UserRole`: 사용자 권한 enum
- [x] `domain/SubscriptionTier`: 무료/프리미엄 구독 등급 enum

구현 필요:

- [ ] UserRepository 분리: Spring Data JPA repository는 `user/infrastructure`에 둔다
- [ ] UserProfile 조회/수정 use case
- [ ] 구독 등급 변경 use case
- [ ] 사용자 통계: 읽기 진행률, 출석, XP, 달란트 aggregate
- [ ] 업적/배지 모델
- [ ] reading calendar 조회 API
- [ ] admin 회원관리 API와 user domain application service 연결
- [ ] soft delete 정책과 감사 로그
- [ ] user domain 단위/통합 테스트

## Bible Domain

Package: `com.example.mypasture.bible`

구현됨:

- [x] 1910 성경 번역본, 66권, 장·절 조회와 단어 검색
- [x] 장별 AI 해설, 구간 해설, 주제, 질문, 교차 참조 DB 모델
- [x] 생성 작업 큐와 OpenAI Structured Outputs adapter
- [x] 실제 성경 파일 기반 장·절 참조 및 전체 구간 자동 검증
- [x] 전체 1910 성경 본문의 PostgreSQL 멱등 import와 절 ID 유지
- [x] 누락 장 `EMPTY` 상태 관리와 DB 기반 페이지 검색
- [x] 원문 해시, 모델, 프롬프트 버전, 토큰, 예상 비용 저장
- [x] 관리자 생성 요청, 목록, 게시, 거절 API와 ADMIN 권한
- [x] 게시된 최신 해설만 반환하는 공개 API
- [x] 모바일 본문/해설 탭, 로딩·미게시·오류 상태
- [x] 생성부터 게시·공개 조회까지 통합 테스트

구현 필요:

- [ ] 1,189개 장 대량 생성용 OpenAI Batch API importer
- [ ] 관리자 해설 비교·편집·검수 UI
- [ ] 중단된 PROCESSING 작업 lease 회수와 재처리
- [ ] 북마크·하이라이트·스크랩의 `verse_id` FK 전환
- [ ] 모바일 SQLite 스냅샷과 증분 오프라인 동기화
- [ ] 번역본별 해설 재사용 및 번역 특화 해설 정책
- [ ] 해설 오프라인 동기화와 오류 신고
- [ ] 목회자·신학 검수 기준과 감사 이력 확정

정책 문서: [성경 장별 AI 해설 생성 및 게시 운영 가이드](bible-commentary-pipeline.ko.md),
[성경 저장 및 오프라인 설계](bible-storage-and-offline.ko.md)

## Pasture Domain

Package 예정: `com.example.mypasture.pasture`

구현됨:

- [ ] 아직 없음

구현 필요:

- [ ] 목장 생성/초대/가입
- [ ] 목장 리더/멤버 role 관리
- [ ] 공지, 댓글, 기도제목 공유
- [ ] 목장 성경 읽기 현황
- [ ] 출석 체크
- [ ] 권한 테스트: leader only action, member only visibility

## Prayer Domain

Package 예정: `com.example.mypasture.prayer`

구현됨:

- [ ] 아직 없음

구현 필요:

- [ ] 기도제목 CRUD
- [ ] 응답 완료 표시
- [ ] 기도일기
- [ ] 목장 공유 범위
- [ ] 알림/리마인더 연동
- [ ] privacy policy와 접근 제어 테스트

## Challenge Domain

Package 예정: `com.example.mypasture.challenge`

구현됨:

- [ ] 아직 없음

구현 필요:

- [ ] 일간/주간/월간 챌린지
- [ ] 퀴즈
- [ ] XP, 달란트, 연속 출석 계산
- [ ] reward transaction과 idempotency
- [ ] leaderboard/cache 전략
- [ ] batch/scheduler 테스트

## Notification Domain

Package 예정: `com.example.mypasture.notification`

구현됨:

- [ ] 아직 없음

구현 필요:

- [ ] FCM token 등록/갱신/삭제
- [ ] 오늘의 말씀 push schedule
- [ ] 목장/기도/챌린지 알림
- [ ] 알림센터 저장소
- [ ] admin push 발송
- [ ] 실패 재시도와 dead-letter 정책

## Reward Domain

Package 예정: `com.example.mypasture.reward`

구현됨:

- [ ] 아직 없음

구현 필요:

- [ ] 달란트 지갑
- [ ] 리워드 상점
- [ ] 교환 요청/승인/취소
- [ ] 거래 원장 기반 정산
- [ ] 중복 지급 방지 idempotency key

## Admin Domain

Package 예정: `com.example.mypasture.admin`

구현됨:

- [ ] 아직 없음

구현 필요:

- [ ] 회원 관리
- [ ] 오늘의 말씀 관리
- [ ] 챌린지/리워드 관리
- [ ] 기도제목 moderation
- [ ] AI 사용량/비용 통계
- [ ] push 발송
- [ ] admin authorization test

## Development Checklist For New Domain

새 도메인을 추가할 때는 아래 순서로 만듭니다.

- [ ] `domain`: entity/value object/enum/domain rule 먼저 작성
- [ ] `application`: use case와 port interface 작성
- [ ] `infrastructure`: DB, Redis, 외부 API adapter 구현
- [ ] `presentation`: request/response DTO와 controller 작성
- [ ] Flyway migration 추가
- [ ] 단위 테스트와 통합 테스트 추가
- [ ] API 문서/Swagger 설명 추가
- [ ] 권한/보안 정책 확인
