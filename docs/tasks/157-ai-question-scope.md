# 작업 계약: AI 질문 관련성 검증 (#157)

## Requirement
성경·기독교·종교와 관련 없는 질문에 성경 구절과 기도문을 반환하지 않는다. 삶의 불안·관계·상처에 대한 목회 상담은 지원한다. 모호하면 원하는 도움을 구체화하도록 안내한다. 시세·날씨·코딩 등 직접 정보 요청은 관련 단어를 장식처럼 붙여도 자동 허용하지 않는다.

## Existing System
서버 main dbbe484 / 앱 main b6fb746에서 시작한다. 서버 Issue #157, 앱 Issue #123. 기존 질문 이해는 키워드 의도 분류 후 모든 입력을 RAG로 보내며 검색 없음만 차단한다. AskBibleAssistantUseCase는 일반/SSE·cache·quota·idempotency 공통 진입점, AiAnswerFeedbackService는 재생성 진입점이다. OpenAiCompletionAdapter의 기존 Responses client, evaluatorModel, timeout/retry, store=false, strict JSON schema를 재사용한다.

## Impact
규모: Medium — 하나의 AI 정책 기능과 앱의 작은 오류 안내 연동이다. DB/인프라 변경 없음. 관련성 호출이 RAG 전에 추가된다. 기존 평가 모델 사용량에 누적 기록하며 실패한 요청은 기존 quota 정책에 따라 일일 완료 횟수에서 제외한다. root의 원래 dirty worktree와 다른 작업은 보존한다.

## Work Breakdown
| 작업 | 역할 / 실제 Agent | 도메인 Owner | 허용 경로 / 산출물 |
| --- | --- | --- | --- |
| 계약·통합·앱 안내·HTTP 문서 | Lead/Frontend: /root | Scripture Intelligence | docs/tasks/157-ai-question-scope.md, http/mypasture-flow.http, ai/presentation/AiApi.kt, 앱 src/features/ai 및 tests/pwa, 앱 docs/tasks |
| 관련성 판단 전체 기능 | Backend Implementation: /root/question_scope_backend | Scripture Intelligence | 서버 ai/application, ai/domain, ai/infrastructure, ai/presentation/AiController.kt 및 AiExceptionHandler.kt, src/test/kotlin/com/example/mypasture/ai |
| 독립 실패·회귀 검증 및 설계 검토 | QA/Reviewer: /root/question_scope_qa | Scripture Intelligence | 두 worktree 읽기, 검증 산출물과 별도 qa-review 보고서만 작성, 제품 코드 수정 없음 |

## Contracts
계약 v1 확정자: /root. 구현·검증 Agent에게 전달한다.

API: 기존 POST /api/v1/ai/chat, /chat/stream, /feedback/{turnId}/regenerate 요청/성공 응답 스키마 유지. 무관한 질문은 HTTP 422 AI_QUESTION_OUT_OF_SCOPE, 모호/uncertain은 HTTP 422 AI_QUESTION_SCOPE_UNCLEAR. SSE는 기존 HTTP 200 연결 이후 event:error로 동일 code/message 반환하고 종료, response/citations/delta/prayer 없음. 메시지는 한국어이며 서버 오류 envelope 유지. 인증·민감정보 동의·소유권·위기 대응 우선순위 유지. 실패 영수증은 기존 멱등성 의미 유지하며 새로운 요청 ID로 수정 질문 가능.

Interface: 새 AiQuestionScopePort.assess(currentQuestion, ownedConversationContext?, runtimeConfig) -> 구조화 assessment(RELATED / PASTORAL_SUPPORT / UNRELATED / UNCLEAR, uncertain, 유효성 및 provider 사용량). 모델 출력의 enum/boolean/완결성을 서버에서 검증. 정확한 내부 타입 이름은 구현 담당이 정할 수 있으나 정책 의미를 변경하지 않는다. application service는 validated && !uncertain && (RELATED || PASTORAL_SUPPORT)일 때만 허용. malformed/refusal/incomplete는 provider 오류로 차단하며 알려진 사용량은 실패에도 기록한다. UNCLEAR/uncertain은 구체화 안내. 모델 예외 시 RAG나 keyword-only production 우회 없음.

분류 입력은 현재 질문을 중심으로 삼는다. 소유권 확인된 최근 대화는 짧은 후속 답변 해석에만 사용하고 명백히 다른 주제로 전환한 요청을 이전 종교 문맥으로 허용하지 않는다. 입력을 지시가 아닌 데이터로 전달. quoted text/분류 조작/prompt injection과 단순 종교 키워드 삽입을 구분하는 지침 포함. 기독교 외 종교·비교·비판적 질문도 RELATED. 불안이라는 단어만을 포함한 시세 조회 같은 복합 정보 요청은 사용자 도움 목적을 분류한다.

적용 순서: 입력 제한/위기 대응/권한 → quota·request reservation → scope assessment 및 server gate → 신규 버전 cache 조회 → RAG → 생성/품질평가 → 기록. clientRequestId 기존 completed replay는 이미 반환된 결과의 재조회 의미를 유지하되 새 요청의 구 cache가 우회하지 않도록 cache version을 변경한다. 재생성도 scope gate 후 검색·생성. 평가 비용과 tokens는 기존 evaluation 사용량 및 total cost에 누적하며 품질 점수와 합격 결과를 scope 점수로 덮어쓰지 않는다. 반환값 없는 provider 장애의 실제 사용량은 미확인으로 기록한다.

DB: 스키마·migration 없음, 기존 사용량/실패 영수증 재사용. Event: 외부 event 변경 없음, 기존 SSE error 사용. Configuration: 새 환경변수·모델·의존성 없음. runtime evaluatorModel과 기존 제한 사용. 운영 배포 승인 없음; PR·CI까지 준비하며 main 자동 운영 배포 병합은 이번 요청에 포함되지 않음.

## Dependencies
계약 확정 → backend/app 독립 구현 → 좁은 검사 → 전체 검사 → 인수인계 → 독립 QA/Review → 수정·재검증 → 최종 커밋/PR CI. 배포 시 서버 선행 후 앱.

## Risks
의미 분류는 확률적이다. mock은 운영 모델 정확도를 증명하지 않으므로 실제 모델 corpus는 비용/자격이 허용되는 검증 경로를 확인해 별도 기록한다. 모델 실패는 fail closed. 추가 latency·비용은 기존 timeout/retry/rate limit과 quota 예약 안에서 제한한다. cache/replay/regeneration/context 우회, 비용 누락, 관련 종교 질문 과차단을 회귀 검증한다. 기존 SSE 간헐 대기 결함 #156의 테스트 조건을 약화하지 않는다.

## Definition of Done
계약 보완 v1.1: 아래 CR-001을 포함한다.
- [x] 사용자 사례 및 정상/오류/불확실 질문 처리
- [x] 일반/SSE/재생성/cache/context 및 사용량 기록 계약 일치
- [x] 전체 서버 검사·앱 검사 및 독립 QA/Review
- [ ] 최종 PR CI 확인
- [ ] 구현/검증/병합/배포 상태 구분 보고

## CHANGE REQUEST: CR-001 — 과거 문맥 의존 질문과 실패 재조회
요청자: Backend / QA. 결정자: Lead /root. 계약 v1 → v1.1, 수락.

### Current
프리미엄 현재 질문의 짧은 후속 답변은 소유권 확인한 최근 문맥으로 해석한다. 기존 개선 답변 API는 원 질문만 받으며 당시 대화 문맥을 반환하지 않는다. 실패한 동일 request ID 재전송은 기존 AI_REQUEST_FAILED를 반환한다.

### Proposed
당시 문맥을 복원할 수 없는 '네'·'첫 번째' 등 원 질문의 개선 답변은 AI_QUESTION_SCOPE_UNCLEAR로 중단하고 기존 답변을 유지한다. 독립적으로 의미가 있는 종교·상담 원 질문은 개선 가능하다. 실패한 같은 request ID의 재전송은 기존 AI_REQUEST_FAILED를 유지하며 GET /requests/{clientRequestId}는 새 범위 오류의 정확한 코드를 보존한다.

### Reason / Impact / Compatibility
최신 문맥을 과거 질문에 대입하면 의미·범위 판단이 잘못될 수 있다. 문맥 스냅샷 저장·보유·삭제·복원 계약을 추가하는 확대 구현은 이번 변경에 포함하지 않는다. 기존의 짧은 후속 질문 개선 흐름이 이제 구체화 안내를 받을 수 있는 제한을 인정한다. 앱은 원 답변을 지우지 않고 안내를 표시한다. 이미 완료된 동일-ID 응답 재조회와 과거 답변 기록은 보존하므로 과거에 반환된 내용은 계속 조회 가능하다. 새로운 질문·캐시 조회·개선 생성에는 새 gate를 적용한다.

### Resolution
[후속 Issue #158](https://github.com/ByeongDoo-Han/mypasture-backend/issues/158)에서 당시 문맥 복원을 추적한다. Backend는 context-only 개선 거절 회귀를 추가하고 QA는 제한·실패 receipt 계약과 앱 표시를 독립 검토한다. 적용 대상 Agent에게 결정 전달 완료. 필수 검증과 운영 배포 승인 조건은 변경하지 않는다.

## Implementation Handoff
Backend /root/question_scope_backend가 관련성 port/model/service, 기존 OpenAI/Local adapter, 일반/SSE/개선 use case, 공개 오류와 사용량 기록을 구현했다. Lead /root는 API interface의 기존 KDoc 계약, HTTP 정상/거절/모호/SSE 사례, 앱 표시·pending 정리·실패 복구를 구현했다. QA/Reviewer /root/question_scope_qa는 제품 코드를 수정하지 않고 독립 검토한다.

서버 제품·테스트 15파일 manifest `/private/tmp/ai-question-scope-checks/backend-source-hashes.json` SHA256 731d7b5550a8ff66b66b2008758ea0715c446b862652133ac606c1de6960f679. Lead API/http를 포함한 17파일은 `qa-server-source-hashes.json` SHA256 f4995ae25ff0162a2e0ed1f2c7f489002ac47570e66aabefaaa2289fc8ebf3f1로 식별하며 현재 파일과 일치한다. base dbbe484. 상세 handoff는 같은 검사 폴더의 `backend-handoff.md`.

### Tests
- Java17 표준 Gradle 좁은 검사: 최종 정책·흐름 40개 통과(36초), 이후 provider transport 보완의 담당9개 통과(6초).
- 최종 `./gradlew test`: exit0, 277개 중274통과·실패0·기존 PG 행 잠금 검사3개 H2에서 건너뜀(96초). 원본 `full-final.log`, `full-final-xml`, `full-final-summary.json`. baseline full과 최종 full을 분리해 소스 변경 전 보고서를 현재 결과로 사용하지 않았다.
- 앱: typecheck/build/hosting exit0, unit114/114, 전체 PWA90/90, 새 scope 안내8/8. 원본 `app-*` 및 `app-source.json`.
- 두 저장소 `git diff --check`: exit0. API/http 예제가 구현 계약과 일치하며 새 SSE/일반 오류를 테스트로 확인했다.

### Risks / Not Run
운영 의미 분류 정확도는 미검증이다. 기존 live 경로는 `./gradlew openAiSmokeTest`이며 일반/CI test에서 openai-live tag를 제외한다. 이 smoke는 모델 접근과 최소 Reply OK 응답만 검증하고 관련성 corpus를 검증하지 않는다. shell key 존재만 boolean으로 확인했으며 새 worktree dotenv·실제 운영 evaluator 권한은 확인하지 않았다. 유료 live 호출을 수행하지 않았다. mock corpus와 HTTP 구조 검증을 운영 모델 정확도로 표현하지 않는다.

실제 네이티브 기기·운영 상담 흐름도 미검증. DB 스키마·migration·잠금/멱등성 변경 없음, 기존 PG CI를 유지해 데이터 관련 회귀를 확인한다. main은 public API 자동 배포에 연결되어 있고 이번 구현 요청의 운영 배포 승인은 없다. 기존 SSE 간헐 대기 #156은 범위 밖이며 timeout/skip/assertion을 약화하지 않았다. 최초 Lead의 Swagger annotation 추가는 기존 의존성 부재로 컴파일 실패해 제거하고 기존 interface KDoc 형식으로 수정했다. 이후 최종 표준 검사를 통과했다.

## 검증 계획
Lead Integration: 두 저장소의 실제 diff·API/http·contract v1.1 및 독립 QA 원본을 확인했다. [독립 QA/Review](157-ai-question-scope-qa-review.md)는 MERGE READY이며 미해결 BLOCKER/HIGH 없음. 알려진 MEDIUM 제한은 CR-001/#158로 수락·추적했다. 최종 17서버/6앱 제품·테스트 hash를 다시 확인해 모두 일치했다. 이후 문서만 갱신하며 최종 커밋 CI는 PR 상태로 확인한다. 운영 배포 승인 없음.

Backend Agent: scope application/provider 단위, 일반/SSE/flow 좁은 검사 후 ./gradlew test(Java17, repo test config), 결과 XML 및 raw log /private/tmp/ai-question-scope-checks. PostgreSQL/Flyway: schema 변경 없음, 기존 CI PG job 유지. Root: typecheck/unit/build/PWA 및 diff --check, 새로운 scope 안내 오류·재시도 browser 사례. QA/Reviewer: 실제 diff와 동일 target 해시·원본 검사 결과를 독립 확인하고 실패/회귀/보안 판단. 필요하면 추가 검사하며 같은 전체 테스트를 근거 없이 반복하지 않는다.
