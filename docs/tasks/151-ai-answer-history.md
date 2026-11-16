# 작업 계약: #151 AI 목자 답변·기도문 기록과 버전 조회

## Requirement
로그인한 사용자가 과거 질문·최종 답변·근거 말씀·추천 기도문을 앱/웹에서 다시 보고, 당시 설정 버전과 재생성 답변의 원본을 구별한다. 운영자에게 실제 사용자 질문의 열람·재실행 권한은 추가하지 않는다. 운영 배포·스토어 출시는 범위 밖이다.

## Existing System
server base `c44a96f`, app base `7941fe9`. 서버 `ai_conversations`는 프리미엄 최근 4턴·30일 문맥, `ai_request_receipts`는 기본 24시간 결과 재전달, `ai_usage_logs`는 비용·모델 설정 ID·재생성 부모 ID를 가진다. 앱 AI turn은 화면 상태이며 재생성은 원본을 교체한다. 관련 server Issue #151, app Issue #114. 결제 PR135가 V43/V44를 예약하므로 새 migration은 본선 V42.1 다음 `V42.2`를 사용한다. 최종 병합 전 재확인한다.

## Impact
Large: 민감한 AI 질문과 생성 응답의 영속 보관, DB/동의·삭제 정책, 서버 API, 앱/웹의 계정별 화면·버전 표시가 모두 변경된다.

## Work Breakdown
| 작업 | 역할 / 실제 Agent | 도메인 Owner | 허용 경로 / 산출물 |
| --- | --- | --- | --- |
| 계획·계약·정책·통합 | Lead /root | Scripture Intelligence + policy coordination | 이 문서, 정책·환경 문서, GitHub 작업, 공유 파일의 최종 통합 |
| 완료 답변 기록 API | Implementation /root/history_backend | Scripture Intelligence | server `src/main/kotlin/.../ai/**`, `src/test/.../ai/**`, 단일 작성자로 `src/main/resources/db/migration/V42.2__ai_answer_history.sql` 및 `http/ai-history.http` |
| 지난 대화 화면 | Frontend /root/history_app | Scripture Intelligence | app `src/features/ai/**`, app `docs/tasks/114-ai-answer-history.md`; 공유 navigation 변경이 필요하면 Lead에게 요청 |
| 실패·회귀 검증 | QA /root/history_qa | 독립 | 읽기 전용 검증 기록. 구현 코드 수정 없음 |
| 설계·보안·성능 검토 | Reviewer /root/history_review | 독립 | 읽기 전용 리뷰 기록. 구현 코드 수정 없음 |

## Contracts
계약 상태/버전: 확정 v1.3. Lead /root 확정, 구현 담당자에게 전달. 2026-10-03 사용자 확정: 원본 질문 생성일 기준 365일 보관하며 원본·개선본은 같은 기록 그룹으로 삭제한다. 사용자 직접 삭제·탈퇴 시 더 일찍 삭제한다.

API:
- `GET /api/v1/ai/history?size=20&cursor=<opaque>`: JWT 본인 전용, `size` 1..50, 미지정 20. 완료된 원본 질문만 `(created_at DESC, turn_id DESC)`로 조회. `{items:[{turnId,questionPreview,createdAt,hasPrayer,modelVersion,revisionCount}],nextCursor}`. cursor는 특정 사용자와 순서에 묶인 opaque 토큰. 비정상 cursor 400. 응답은 민감 데이터이므로 `Cache-Control: no-store`.
- `GET /api/v1/ai/history/{turnId}`: JWT 소유자 전용. 원본 또는 개선본 ID로 조회해 `{rootTurnId,question,versions:[{turnId,kind,createdAt,answer,citations,prayer,followUpQuestion,modelVersion,generatorModel,responseSchemaVersion}]}`. 버전은 생성 시각 순, 원본 보존. 타인/미존재 404.
- `DELETE /api/v1/ai/history/{turnId}`: JWT 소유자 전용. 원본과 개선본 기록을 삭제해 204, 반복 삭제도 204. 사용량/비용·멱등성 영수증의 별도 정책에는 영향 없음. CSRF/JWT 기존 방식 사용.
- 기존 `/chat`, `/chat/stream`, `/requests`, `/feedback/regenerate` 응답·인증은 유지. 성공 완료 시 동일 `turnId`로 기록. `clientRequestId` 재전송, 캐시 응답, SSE 네트워크 종료에도 중복 기록 없음.

DB:
- `ai_answer_history`: `turn_id UUID PK`, `user_id UUID FK users ON DELETE CASCADE`, `parent_turn_id UUID NULL`(답변 수정본), `conversation_id UUID NULL`(만료될 수 있는 문맥 참조이며 FK 없음), `question_ciphertext TEXT`, `response_ciphertext TEXT`, `question_preview_ciphertext TEXT`가 불필요하면 만들지 않음, `model_config_version_id UUID NULL`(버전 테이블 FK는 운영 보존 정책에 맞춰 판단), `model_version_number BIGINT NULL`, `generator_model VARCHAR(80) NULL`, `response_schema_version VARCHAR(80)`, `assistant_version VARCHAR(80)`, `created_at TIMESTAMPTZ`. `user_id,created_at DESC,turn_id DESC` 목록 index, `user_id,parent_turn_id` 개선본 index. `parent_turn_id`는 최상위 원본 turn ID로 정규화하고 사용자 소유를 검증한다. usage log의 부모 ID는 직접 재생성 대상 ID로 기존 의미를 유지한다. 암호화는 기존 AES-GCM 패턴을 재사용하되 history 전용 도메인 키. 기존 migration 수정 없음. 과거 만료 데이터 소급 복원 없음.
- 보유기간·cleanup: 원본 질문과 연결된 모든 개선본은 원본 질문 생성일 기준 365일 후 worker가 함께 삭제하며 사용자가 삭제하거나 탈퇴하면 더 빨리 삭제한다. 기존 프리미엄 대화 문맥 30일, 응답 영수증 기본 24시간, 사용량 기록 1년과 별도 정책이다. AI 민감정보 동의·개인정보 처리방침은 1.1.0으로 버전 갱신하고 과거 원문은 보존한다. 기존 사용자는 AI 기능 진입 시 새 민감정보 동의를 받는다.

Event: 없음.
Interface: backend 새 history 응답 DTO와 app Zod 계약은 위 API shape를 따른다. `prayer`는 `{title,content}|null`, `citations`는 BibleCitation[], `followUpQuestion`은 `{question,options,allowSkip}|null`, `modelVersion`은 number|null, `assistantVersion`은 생성 파이프라인의 명시적 버전 문자열이다. AI 프롬프트·검색·답변 정책을 변경할 때 버전 상수를 갱신한다. 원본에 linked 개선본이 없어도 versions는 원본 한 개. 개선본의 원본 기록이 이미 삭제·만료되었거나 기능 적용 전이라 없으면 개선본도 기록하지 않아 삭제한 기록이 되살아나지 않게 한다. 응답 저장이 실패하면 사용량 완료와 기록의 경계가 갈라지지 않도록 같은 DB 트랜잭션에서 확정한다. 실제 사용자의 질문을 운영 평가에 자동 제공하지 않는다.
Configuration: 새 API·DB 선행 후 앱 배포. 새 비밀값 없음. 정책 문서 버전 갱신 필요 여부는 보유기간 결정에 따른다.

## Dependencies
서버 계약/DB → 앱 연동. 기능 코드는 경로를 분리해 병렬 진행. 사용자 확정 정책 기간(원본 생성일 기준 365일) → cleanup/문서/최종 검증. 서버 PR은 운영 자동 배포 연결로 사용자 배포 승인 전 병합하지 않는다. 앱 PR도 선행 API 준비 전 병합하지 않는다.

## Risks
민감한 종교·감정 입력 노출, 다른 계정 캐시, 재생성 원본 유실, 중복·부분 성공, 보유기간 고지 불일치. API 소유권·암호화·no-store·영속 unique·계정별 query key·삭제/탈퇴/cleanup을 검사한다. 새 라이브러리/인프라를 추가하지 않는다.

## Definition of Done
- [x] 질문·답변·기도문·근거 및 버전의 목록/상세/삭제 동작
- [x] 원본과 개선본 보존, 계정 격리, 중복/장애 경로 검증
- [x] 정책 보유기간과 cleanup/동의 안내 일치
- [x] 서버 전체/PG Flyway, 앱 typecheck/unit/web/PWA, 독립 QA/Review
- [ ] 최종 커밋 CI 확인과 배포 승인 경계 기록

## 검증 계획
서버 구현 담당 focused+전체 Gradle, PostgreSQL/Flyway. 앱 담당 typecheck, unit, web build, PWA 및 모바일·데스크톱 화면. QA는 실제 diff·테스트 결과 확인과 실패 조건, Reviewer는 설계·보안·성능·호환성. Lead는 두 저장소 결과 통합 및 CI 확인.

## Change Request CR-002
Current: 설정 버전만 각 답변에 저장. Proposed: 프롬프트·검색 정책 코드의 변경도 구분할 `assistantVersion`을 이력에 저장하고 목록/상세에 반환. Reason: 모델 설정만으로 AI 목자 품질 개선 버전을 식별할 수 없음. Impact: 새 migration 컬럼, API DTO, 앱 파서/표시, 테스트. Compatibility: 신규 API 계약 v1.2이며 기존 채팅 응답 변경 없음. Lead Decision: 수락, 2026-10-02. 구현 담당자 양쪽에 전달 완료.

## Change Request CR-001
Current: 원본이 삭제된 상태에서 개선본을 생성하면 홀로 남는 기록이 생길 수 있음. Proposed: 원본 기록이 없으면 개선본은 이력에 저장하지 않고 생성 응답·사용량 기록은 기존대로 처리. Reason: 사용자가 삭제한 이력의 재등장 방지. Impact: 재생성 이력 저장 조건과 테스트. Compatibility: 기존 재생성 API 동작 유지. Lead Decision: 수락, 2026-10-02. 서버 담당자에게 전달 완료.

## 재개 계획 (2026-10-03)

최신 origin/main 확인: server c44a96f / app 7941fe9, 두 기존 feature worktree와 동일. Issue #151/#114는 OPEN. 기존 dirty 기능 변경을 이어서 구현하며 root main-2의 관련 없는 문서는 변경하지 않는다.

- Lead /root: 공유 정책·계정삭제·CI·GitHub 통합, 최종 검증 근거와 승인 경계.
- Backend /root/history_server_finish: ai 기능 및 테스트, 신규 migration과 http/ai-history.http.
- Frontend /root/history_client_finish: features/ai, package.json 테스트 명령, tests/pwa/ai-history.spec.ts 및 app 작업 기록.
- QA /root/history_independent_qa: 코드 변경 없이 실패·회귀 재현, docs/tasks/151-history-qa-report.md 단일 작성자.
- Reviewer: 구현 인수인계 후 별도 Agent에 설계·보안·성능·변경 검토 배정.

사용자 “1번 진행해줘”는 대화 기록 구현·검증·PR 준비에 적용한다. 실제 API가 연결된 서버의 main 병합은 운영 자동 배포를 시작하므로 이번 기능은 PR/CI까지 준비하고 운영 배포 승인을 요청한다. 앱은 선행 API 배포 전 병합하지 않는다. 스토어 출시는 범위 밖이다.

## Change Request CR-003: 다단계 개선 답변의 기록 그룹

Current: history.parent_turn_id가 usage의 직접 부모 ID를 그대로 사용해 개선본의 개선본이 원본 목록·삭제·cleanup에서 누락된다.
Proposed: history는 최상위 root ID로 정규화한다. 직접 부모가 본인 기록인지 확인하고 root 행 잠금 이후 재확인한다. usage의 직접 부모 의미는 유지한다.
Reason: 원본과 모든 개선본 보존 및 삭제·보관기간의 완전성.
Impact: history writer와 다단계 재생성·상세·삭제·cleanup 회귀 테스트. 앱 응답 shape는 유지하고 versions에 모든 개선본을 표시한다.
Compatibility: 기존 chat/feedback API와 새 history 응답 형식 유지. 아직 미적용 신규 schema이며 기존 데이터 migration은 없다.
Lead Decision: 수락, 계약 v1.3. Backend 구현, Frontend/QA 전달 완료.

## Change Request CR-004: 계정별 최신 AI 동의 상태

Current: 클라이언트 정책 동의 query key에 계정이 없어 계정 전환 시 이전 동의 상태를 재사용할 수 있다.
Proposed: 정책 동의 조회·갱신 API에 시작 계정을 전달하고 계정별 캐시·응답 확인을 적용한다.
Reason: 새 AI 동의와 민감 기록 진입 상태의 계정 격리.
Impact: app features/policy/{api,PolicyConsentGate,PolicyCenterScreen} 및 회귀 테스트.
Compatibility: 서버 정책 API와 저장 정책 유지, 앱 내부 API에 선택적 시작 사용자 인자 추가.
Lead Decision: 수락. 위 앱 경로 단일 작성자를 Frontend /root/history_client_finish로 재배정, QA 통보.

## 검증 환경 확인

- 2026-10-03 main fetch: server c44a96f / app 7941fe9, 기존 작업 base와 동일.
- 운영 migration 로그(2026-10-01T10:34:02Z): Successfully applied v42.1. 열린 결제 PR #135의 V43/V44는 미병합. 신규 V42.2는 현재 순서에 맞으며 병합 직전 재확인한다.
- Docker Desktop 시작 후 일회용 pgvector/pgvector:pg17, localhost:15451/history_test 준비. 운영 DB에 쓰기 없음.
- 서버 PG CI는 기존 postgres-auth job의 일회용 PG16에서 신규 history 두 클래스를 실제 재실행한다. 로컬 PG17과 CI PG16을 구분한다.
- 모든 실행 로그: /private/tmp/mypasture-history-checks (git 제외).

## Change Request CR-005: 탈퇴와 AI 완료의 잠금 순서

Current: AI 완료는 usage 행 잠금 뒤 history INSERT의 user FK 잠금을 얻고, 탈퇴는 user 잠금 뒤 usage를 삭제해 서로 기다릴 수 있다.
Proposed: complete는 reservation의 userId를 잠금 없는 조회로 찾고 활성 user 행을 먼저 잠근 뒤 usage·history root 순으로 처리한다. 기존 예약 경로와 순서를 통일한다.
Reason: 신규 기록 FK 추가에 따른 PostgreSQL 교착과 탈퇴 후 기록 생성 방지.
Impact: JdbcAiUsageAdapter.complete 및 실제 PG 완료·탈퇴 경합/멱등성 테스트.
Compatibility: API·기존 usage parent 의미·과금 정책 유지.
Lead Decision: 수락, Backend 담당 구현 및 독립 QA/Review 재확인.

## Change Request CR-006: 생성 파이프라인과 응답 캐시 버전

Current: 응답 캐시 범위에는 모델 설정과 응답 schema가 있으나 AI_PASTOR_VERSION은 없어 pipeline만 갱신하면 이전 답변을 새 pipeline 버전으로 기록할 수 있다.
Proposed: cache scope에 AI_PASTOR_VERSION을 추가한다.
Reason: 답변 당시 AI 버전 표시의 신뢰성.
Impact: AskBibleAssistantUseCase 캐시 키 및 관련 행동 검사.
Compatibility: API 변경 없음, 이전 캐시는 새 namespace에서 재사용하지 않는다.
Lead Decision: 수락, Backend 구현 및 Reviewer 확인.

## Change Request CR-007: 보관기간 경과 기록의 조회

Current: worker 일일 파기 전까지 생성일 365일이 지난 기록을 조회할 수 있다.
Proposed: 목록·상세는 원본 질문의 생성일 365일 경계를 적용하고 만료 원본의 개선 기록은 저장하지 않는다. 정기 파기 작업이 원본과 개선본을 함께 물리 삭제한다.
Reason: 사용자 확정 보관기간과 조회·재생성 의미 일치.
Impact: history service/writer/repository와 만료 경계 테스트, 정책 운영 문서.
Compatibility: API shape 유지, 만료 상세는 미존재와 같은 404.
Lead Decision: 수락. 물리 파기는 일일 배치이며 장애 시 재시도·작업 모니터링이 필요함을 운영 기록에 명시한다.

## Change Request CR-008: 실패 완료와 탈퇴의 잠금 순서

Current: fail은 usage→receipt를 잠그고 탈퇴는 user→receipt→usage를 삭제해 경합 시 역순 대기 가능성이 있다. 신규 history 저장 실패의 복구 경로에도 해당한다.
Proposed: fail도 활성 user를 먼저 잠근 뒤 usage→receipt 순으로 처리하고 이미 탈퇴/삭제된 사용자는 no-op한다.
Reason: 실패 복구가 교착으로 원래 오류를 가리는 회귀 방지.
Impact: JdbcAiUsageAdapter.fail 및 실패·탈퇴 회귀 검사.
Compatibility: API 및 기존 과금/실패 의미 유지.
Lead Decision: 수락, Backend 구현 및 실제 PostgreSQL 검증.

## Change Request CR-009: 요청 복구 worker의 완료 상태와 탈퇴 경합

Current: 기존 stale worker는 먼저 읽은 receipt entity의 PENDING 상태를 잠금 뒤 재사용해 완료된 usage의 receipt를 FAILED로 바꿀 수 있다. usage→receipt 순서도 탈퇴와 역순이다.
Proposed: user→usage→receipt 순서로 잠그고 usage가 PENDING인 후보만 복구한다. 완료·실패 최종 상태를 보존하고 탈퇴한 사용자는 건너뛴다.
Reason: 새 history와 완료 receipt의 원자성 및 재전송 멱등성에 직접 영향을 준다.
Impact: AiRequestReceiptPersistence 및 기존·신규 AI 경합 검사. Issue #152로 발견·검증을 추적한다.
Compatibility: API·과금 정책 유지, 이전부터 있는 복구 worker의 잘못된 완료 상태 덮어쓰기를 막는다.
Lead Decision: 수락. Backend 허용 경로에 위 worker와 관련 테스트를 추가하고 독립 QA/Review를 갱신한다.

## 클라이언트 PR 준비

- app PR https://github.com/ByeongDoo-Han/mypasture-app/pull/115
- app commit 6c4cc95, 기능 소스는 최종 검사 hash와 일치하며 추가 변경은 작업 기록뿐이다.
- 97 unit / 58 PWA(그 중 AI 기록 UI desktop/mobile 12개), typecheck/web build/diffcheck 모두 exit 0. 독립 앱 QA PASS.
- 서버 API 준비 전 앱 병합·운영 배포하지 않는다.

## 최종 통합 검증 및 인수인계

Lead는 실제 서버·앱 diff와 API/DB/정책·CI 계약을 확인하고, 실행 영향 서버 23개 파일과 앱 9개 파일의 해시가 검사 대상과 같음을 직접 계산했다. 독립 QA /root/history_independent_qa는 PASS, Reviewer /root/history_architecture_review는 MERGE READY로 최종 보고했다. 구현하지 않은 Agent가 소스와 실행 원본을 검토했으며 QA/Review가 같은 전체 검사를 재실행한 것으로 표현하지 않는다.

| 최종 검사 | 대상 / 환경 | 결과 / 시간 | 근거 |
| --- | --- | --- | --- |
| 서버 담당 AI 3클래스 | 최종 미커밋 소스, H2 | exit 0, 28개 중 26 통과 / 2 PG 전용 skip / 실패 0, 49초 | server-focused-worker-final.log 및 XML |
| 서버 필수 전체 | 같은 최종 소스, H2 | exit 0, 251개 중 249 통과 / 2 PG 전용 skip / 실패 0, 112초 | server-all-worker-final.log 및 52 suite XML |
| 실제 PostgreSQL | 일회용 PG17.9, history 2클래스 | exit 0, 13/13 통과, skip/실패 0, 76초. Flyway 44 migration 검증·42.2 및 Hibernate validate | server-postgres-worker-final.log/XML, server-postgres-schema.txt |
| 앱 typecheck/unit/web | app 6c4cc95 기능 소스 | 모두 exit 0, unit 97/97 통과 | app-validation-manifest.json 및 app 로그 |
| 앱 PWA/UI | Chromium desktop/mobile, mock API | exit 0, 전체 58/58 중 기록 UI 12개, 2.1분; 두 크기 시각 확인 | app-pwa.log 및 PNG |
| diff / CI 환경 | 두 저장소 및 YAML/env 계약 | git diff --check exit 0, PG CI env 이름과 테스트 설정 일치 | Lead와 독립 Agent 직접 확인 |

전체 로그/원본 XML과 두 소스 manifest는 /private/tmp/mypasture-history-checks에 보존한다. server-tested-source-manifest.json 자체 SHA256: 3ef95d0177e74d994e717287139717d8363caa7a338f41b12332491d0494fc2e. 최초 cache fixture 실패·PG 예외 wrapper 검사 실패는 별도 원본으로 남기고 최종 결과로 재사용하지 않았다.

현재 앱 PR #115 / commit 6c4cc95의 mobile-ci는 SUCCESS. 서버 커밋/PR와 그 최종 CI는 통합 후 확인한다. 운영 API/DB를 변경하지 않았고 실제 네이티브 기기·새 API가 배포된 운영 서비스의 통합 확인은 아직 수행하지 않았다. 기능 적용 전 저장되지 않은 과거 질문을 소급 복원하지 않는다.
