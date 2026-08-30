# My Pasture Feature Checklist

작성 기준: 현재 Spring Boot 백엔드 프로젝트 상태

## 상태 범례

- [x] 구현됨: 코드가 있고 빌드 대상에 포함됨
- [~] 부분 구현: 구조나 골격은 있으나 운영 기능은 미완성
- [ ] 미구현: 아직 코드 없음

## 1. 회원가입 / 로그인

- [ ] Google OAuth 로그인
- [ ] Apple OAuth 로그인
- [ ] Kakao OAuth 로그인
- [ ] 이메일 회원가입
- [ ] 이메일 로그인
- [ ] JWT Access Token 발급
- [ ] Refresh Token 저장/회전
- [~] Spring Security 기본 설정
  - 현재 `SecurityConfig`가 존재하지만 대부분 endpoint를 permit 처리합니다.
  - 다음 단계: JWT filter, 인증 실패 핸들러, role 기반 권한 체크 추가.
- [~] Auth Controller 기본 health endpoint
  - 현재 `/api/v1/auth/health`만 존재합니다.

## 2. 성경 읽기

- [x] 성경 DB 스키마
  - `bible_books`, `bible_verses`, `bible_embeddings` 테이블이 Flyway migration에 정의되어 있습니다.
- [x] 성경 검색과 RAG index
  - `BibleRetrievalPort`와 `JdbcBibleRetrievalAdapter`가 있습니다.
  - pgvector cosine Top-K 검색과 실제 `bible_verses` 재검증을 적용했습니다.
  - 본문 hash 기반 embedding 재생성과 관리자 진행률 API가 있습니다.
- [x] 성경 읽기 API
- [ ] 성경 검색 API
- [ ] 성경 AI 해설 API 분리
- [~] 하이라이트 DB 스키마
- [x] 절 단위 북마크 API와 DB 제약
  - 사용자별 최신순 페이지 조회, 장별 저장 상태 조회, 저장·삭제 API가 있습니다.
  - `(user_id, verse_id)` 유니크 제약과 사용자 행 잠금으로 중복 저장을 방지합니다.
- [~] 읽기 진행률 DB 스키마
- [ ] 읽기 기록 API
- [ ] 오프라인 읽기 패키지 API

## 3. AI 성경 비서

- [x] 입력 300자 제한
  - `AskAiRequest` validation과 `AskBibleAssistantUseCase`에서 제한합니다.
- [x] 무료 회원 일일 사용량 제한 로직
  - `AiUsagePort.countToday()` 기준으로 제한합니다.
- [x] RAG 강제 구조
  - `BibleRetrievalPort`로 관련 구절을 먼저 조회하고, 구절이 없으면 답변하지 않습니다.
- [x] AI 캐시 포트
  - `AiCachePort`와 Redis adapter가 있습니다.
- [x] AI 사용량 저장 포트
  - embedding/prompt/completion tokens, 각 비용, retrieval strategy, cache hit을 저장합니다.
- [~] Redis 동일 질문 캐싱
  - SHA-256 normalized question key 기반 캐시가 있습니다.
  - 다음 단계: embedding 기반 semantic cache 추가.
- [x] OpenAI completion adapter
  - Responses API 일반/streaming 호출, timeout, delta 전 최대 3회 retry를 구현했습니다.
- [x] Streaming 응답 API
  - `delta`, `citations`, `usage`, `done`, `error` SSE 이벤트와 전송 전 citation 검증을 적용했습니다.
- [ ] 성경 상담
- [ ] 성경 Q&A
- [ ] 말씀 추천
- [ ] 기도문 작성
- [ ] 기독교적 마인드 코칭
- [ ] 오늘의 묵상 생성

## 4. 오늘의 말씀

- [x] 오늘의 말씀 DB 테이블
- [x] 하루 1회 AI 생성 batch
  - D+1 작업을 멱등 큐에 등록하고 lease worker가 RAG 원문을 확정한 뒤 AI 묵상 초안을 생성합니다.
- [x] 관리자 검수, 수정, 반려, 게시
- [x] 게시 누락·생성 실패·생성 지연 자동 감지와 관리자 사건 처리
- [x] 실제 성경 절 기반 수동 복구 초안과 늦은 AI 결과 덮어쓰기 방지
- [x] 관리자 이메일 incident outbox, lease, 재시도
- [x] 이메일 outbox 상태·기간 페이지 조회, 마스킹, 실패 건 수동 재처리와 감사 로그
- [x] 생성 메타데이터와 token/예상 비용 저장
- [x] 사용자 공통 결과 저장
- [x] 묵상
- [x] 실천 질문
- [x] 푸시 알림 설정과 기기별 신뢰성 있는 발송
  - lease 만료 복구, 지수 backoff, 최대 재시도와 영구 토큰 비활성화를 적용했습니다.

## 5. 성경 챌린지

- [~] 챌린지 DB 스키마
  - `challenges`, `challenge_participants`가 정의되어 있습니다.
- [ ] 일간 챌린지
- [ ] 주간 챌린지
- [ ] 월간 챌린지
- [ ] 퀴즈
- [ ] XP 지급
- [ ] 달란트 지급
- [ ] 연속 출석

## 6. 목장

- [~] 목장 DB 스키마
  - `pastures`, `pasture_members`가 정의되어 있습니다.
- [ ] 목장 생성 API
- [ ] 초대 코드/초대 링크
- [ ] 기도제목 공유
- [ ] 댓글
- [ ] 성경 읽기 현황
- [ ] 공지
- [ ] 출석
- [ ] 리더 관리

## 7. 기도

- [~] 기도 DB 스키마
  - `prayers` 테이블이 정의되어 있습니다.
- [ ] 기도제목 작성 API
- [ ] 응답 완료 표시
- [ ] 기도일기
- [ ] 목장 공유

## 8. 성경일기

- [~] 성경일기 DB 스키마
  - `journal_entries` 테이블이 정의되어 있습니다.
- [ ] 일기 작성 API
- [ ] AI 리뷰
- [ ] 감정 분석
- [ ] 추천 말씀
- [ ] 묵상 질문

## 9. 말씀 카드

- [ ] 배경 선택
- [ ] 이미지 생성/저장
- [ ] Cloudflare R2 업로드
- [ ] SNS 공유

## 10. 달란트

- [ ] 리워드 상점
- [ ] 교환
- [ ] 기록
- [ ] 중복 지급 방지 idempotency

## 11. 알림센터

- [x] 사용자 공통 알림과 읽음 상태 테이블
  - 공통 콘텐츠는 사용자 수만큼 복제하지 않고 `notification_broadcasts` 한 건으로 저장합니다.
  - 개별 읽음 receipt와 전체 읽음 watermark를 함께 사용해 저장량을 줄였습니다.
- [x] cursor 기반 알림 목록과 미읽음 개수 API
  - 계정 생성 이후의 유효한 알림만 최대 50건씩 조회하며 임의 URL 대신 허용된 목적지 enum을 반환합니다.
- [x] 단건·전체 읽음 처리
  - 단건 읽음과 전체 읽음은 반복 요청에도 같은 결과를 내며 사용자 간 상태가 분리됩니다.
- [x] 오늘의 말씀 게시와 인앱 알림 생성 연동
  - 게시 transaction 안에서 알림을 생성하고 event key 유일 제약으로 중복 생성을 막습니다.
- [x] 관리자 알림 운영 집계 API
  - 공통 알림, 기기 작업, FCM provider 접수, 재시도, 최종 실패와 앱 열람을 구분합니다.

## 12. 프로필

- [~] User Entity
- [ ] 프로필 조회/수정 API
- [ ] 통계
- [ ] 업적
- [ ] 읽기 캘린더

## 13. 관리자

- [ ] 회원관리
- [x] 오늘의 말씀 관리
- [ ] 챌린지 관리
- [ ] 리워드 관리
- [ ] 기도제목 관리
- [ ] 푸시 발송
- [ ] AI 사용량 조회
- [ ] 통계

## 14. 인프라 / 운영

- [x] Gradle build 통과
- [x] Flyway migration 초안
- [~] PostgreSQL 연결 설정
- [~] Redis 연결 설정
- [ ] Docker Compose 운영 정리
- [ ] GitHub Actions CI
- [ ] Nginx
- [ ] GCP 배포
- [ ] Observability metrics/log/tracing

## 15. 개인정보와 정책 동의

- [x] 한국어 서비스 이용약관과 개인정보 처리방침
- [x] 계정 필수 수집·이용 동의와 AI 민감정보 별도 동의
- [x] 선택 마케팅 알림 동의 및 철회 API
- [x] 정책 원문 버전 관리와 공개 조회 API
- [x] 사용자별 최신 동의 상태와 추가 전용 감사 이력
- [x] 이메일 회원가입 필수 동의 검증
- [x] 모바일 회원가입·기능 진입 동의 화면과 원문 보기
- [ ] 실제 사업자·담당자 정보 확정 및 법률 검토
- [ ] App Store App Privacy·Google Play Data safety 신고와 실제 데이터 흐름 대조
- [ ] 관리자 정책 버전 게시·재동의 대상 집계 화면

## 다음 개발 추천 순서

1. Redis 분산 rate limit
2. embedding 기반 유사 질문 semantic cache
3. 북마크·하이라이트·스크랩의 실제 `verse_id` 연결
4. AI 대화 conversation/session 저장과 삭제 정책
5. 관리자 AI 사용량·비용·RAG 품질 대시보드
6. Pasture/prayer/challenge 도메인 API 확장

## 기능 추가 시 읽을 코드 위치

- AI 정책/흐름: `AskBibleAssistantUseCase.kt`
- AI 외부 시스템 연결점: `AiPorts.kt`
- REST API: `presentation/*/*Controller.kt`
- Redis 캐시: `RedisAiCacheAdapter.kt`
- OpenAI 연동: `OpenAiCompletionAdapter.kt`
- DB/RAG 검색: `JdbcBibleRetrievalAdapter.kt`
- AI 사용량/비용 로그: `JdbcAiUsageAdapter.kt`
- 설정값: `AiProperties.kt`, `application.yml`
- DB 스키마: `src/main/resources/db/migration/V1__init.sql`
