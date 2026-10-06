# 독립 QA / Review: AI 질문 관련성 (#157)

- 담당자: `/root/question_scope_qa` — 제품 코드 작성 없음, 이 보고서와 로컬 검증 산출물만 작성.
- 계약: [v1.1 작업 계약 및 CR-001](157-ai-question-scope.md).
- 판정: **MERGE READY — 구현 및 필수 로컬 검사 검토 완료. 최종 PR CI 통과와 운영 자동 배포 병합 승인 조건을 대체하지 않는다.** 미해결 BLOCKER / HIGH 없음.
- 대상: 서버 base `dbbe484cae8d78df9c1465c64f89503bb4df582e`, 앱 base `b6fb7467fc4866607847adf6088886fbdd307687`의 미커밋 변경.
- 검토 방식: 실제 전체 diff와 새 파일, 관련 use case·provider·usage/receipt/feedback 영속 코드·대화 문맥·앱 오류/복구 흐름을 독립 확인. 구현 담당자가 실행한 검사 원본을 재집계하고 현재 파일 해시를 대조했다. 동일 전체 검사를 재실행하지 않았다.
- 참고: 기본 브랜치에 `docs/development/multi-agent-workflow.md`가 없어 제공된 AGENTS.md의 QA/Review 필드를 이 문서에 직접 기록한다.

## 대상 식별

원본과 SHA256은 `/private/tmp/ai-question-scope-checks/`에 보존한다.

| 대상 | Manifest | SHA256 | 현재 파일 대조 |
| --- | --- | --- | --- |
| Backend 담당 제품·테스트 15개 | `backend-source-hashes.json` | `731d7b5550a8ff66b66b2008758ea0715c446b862652133ac606c1de6960f679` | 모두 일치 |
| 서버 전체 src/http 17개 (Lead API/http 포함) | `qa-server-source-hashes.json` | `f4995ae25ff0162a2e0ed1f2c7f489002ac47570e66aabefaaa2289fc8ebf3f1` | 모두 일치 |
| 앱 제품·테스트 6개 | `app-source.json` | `f4ac791ffa9c9df6199bbcbd3cac6f82b94db812994390711b44e1abdd912844` | 모두 일치 |

## QA 결과

| 범위 | 결과 | 실제 근거 및 한계 |
| --- | --- | --- |
| 위기·입력 제한·소유권·동의·인증 | PASS | 기존 controller 동의 및 security 우선순위 유지, owned conversation 확인 후 scope, regeneration source ownership/hash 확인. 새 crisis/stranger/unknown-provider 회귀와 기존 전체 서버 검사. |
| 지원 종교·비교·비판 및 목회 상담 | PASS (mock/계약) | 로컬 corpus 종교 비교·비판·종교어 없는 불안·금융 상황 배경 상담, provider 요청 지침 확인. 실제 모델 정확도를 검증한 결과는 아님. |
| 무관 / UNCLEAR / uncertain | PASS | service native enum 검증 + validated/confident gate, cache get 전에 실패 및 RAG 단계/delta 부재 통합 검사. 정상 HTTP 422 오류의 답변 블록 부재, receipt exact code. |
| 모델 출력·장애 | PASS | unknown enum, 잘못된 boolean, 필드 누락/추가, trailing JSON, malformed JSON, incomplete, refusal은 provider 오류. raw HTTP JSON decode 실패도 provider 오류로 차단. CancellationException은 재전파, 운영 keyword fallback 없음. |
| 현재 질문 / 대화 문맥 | PASS (mock/계약) | provider는 current question과 owned 최근 2개 turn을 별도 JSON 데이터로 전달; 명확한 새 시세 요청은 종교 문맥으로 허용하지 않는 지침·흐름 검사. 짧은 followup의 context 전달 확인. |
| SSE | PASS | scope 오류 후 event:error 및 종료, response/citations/delta/PRAYER 부재. 기존 완료 및 간헐 대기 관련 검사 timeout/assertion 약화 없음. |
| 캐시 | PASS | scope gate가 cache get보다 앞서고 namespace v3, 새 cache 요청에 scope 재평가. cache hit는 과거 generation/embedding/evaluation 비용을 0으로 만든 뒤 현재 scope 비용만 추가. |
| 완료 / 실패 영수증 | PASS (v1.1) | 완료 same-ID 재조회는 gate 없이 과거 응답 재전송하는 명시적 호환성 예외. 새 생성·새 cache 요청 우회와 구분. 실패 같은-ID 재전송은 기존 generic AI_REQUEST_FAILED, GET receipt는 정확한 scope code 보존. |
| 재생성 | PASS (v1.1 제한 포함) | 원 질문 소유권/hash, crisis, reservation 후 scope gate. scope 거절 시 실패 비용·feedback FAILED, 원 완료 답변 보존. 문맥만으로 해석 가능한 원 질문은 UNCLEAR로 거절하는 수락된 제한. |
| 비용 / quota | PASS | 알려진 scope 사용량은 거절·잘못된 모델 출력·downstream 검색 실패에 기록. quality 점수/합격을 덮어쓰지 않고 evaluation/total 비용에 누적. quality evaluation off에서도 evaluator_model 영속. 실패는 완료 사용횟수에서 제외. provider 응답을 받지 못한 실제 비용은 미측정이며 0 비용으로 확인됐다는 뜻이 아님. |
| 앱 오류 및 복구 | PASS | HTTP/SSE(status 0)/GET FAILED scope 안내 후 pending 해제, 수정 질문 새 request ID, 응답·기도·구절 부재. regeneration scope 안내는 기존 답변 유지. mock PWA 4개 시나리오 × 2개 viewport. |
| 화면 확인 | PASS | QA가 mobile SSE 및 desktop recovery PNG 직접 열어 안내 가독성·구절/기도 부재·수정 가능한 입력·가로 overflow 부재 확인. 전체 UI 리디자인은 범위 밖. |
| 실제 운영 모델 corpus | NOT RUN | opt-in `./gradlew openAiSmokeTest` 경로와 `@Tag("openai-live")`를 확인했으나 기존 smoke는 model access + 최소 Reply OK만 검증한다. 일반 test/CI에서 제외된다. Lead는 process key 존재 boolean만 확인; worktree dotenv 및 운영 evaluator 권한/설정은 미확인. 유료 smoke/corpus 호출은 하지 않았다. 운영 의미 분류 정확도·실제 latency/cost benchmark는 별도 검증 필요. |
| PostgreSQL / Flyway | 별도 새 로컬 실행 없음 | schema/migration/lock/constraint 변경 없음. 기존 PostgreSQL 조건부 row-lock 검사 3개는 H2 전체 검사에서 SKIP, 통과로 계산하지 않는다. 해당 PG CI는 기존대로 필수. |

## 검사 원본과 실행 주체

QA는 아래 로그·XML·JSON을 직접 읽고 집계했다. 종료 코드는 실행 주체 인수인계와 최종 성공 로그를 함께 확인했다. 중간 실패/이전 source의 `full-baseline-xml`은 최종 통과 근거로 사용하지 않는다.

| 실행 담당 | 명령 / 환경 | 결과 | 원본 |
| --- | --- | --- | --- |
| Backend Agent | Java 17, 기존 Spring test/H2 설정; `./gradlew test --tests 'com.example.mypasture.ai.AiQuestionScope*' --tests 'com.example.mypasture.ai.AiAssistantFlowTests' --tests 'com.example.mypasture.ai.AiControllerStreamingTests' --tests 'com.example.mypasture.ai.OpenAiCompletionAdapterTests'` | exit 0, 40 tests, 0 failure/error/skip, 36s. 이후 targeted transport 변경만 아래에서 재검증. | `narrow-final2.log`, `narrow-final2-xml/` |
| Backend Agent | `./gradlew test --tests 'com.example.mypasture.ai.OpenAiCompletionAdapterTests'` | exit 0, 9 tests, 0 failure/error/skip, 6s. | `provider-final.log`, `provider-final-xml/` |
| Backend Agent | 최종 source에서 `./gradlew test` | exit 0, 55 XML suites, 277 discovered / 274 passed / 3 skipped / 0 failure/error, 1m36s. `:test` 실제 실행, cached test 결과 아님. | `full-final.log`, `full-final-xml/`, `full-final-summary.json`, `backend-handoff.md` |
| Lead /root | 앱 `node --experimental-strip-types --test src/features/ai/idempotency.test.ts` | exit 0, 3 pass, 0 fail/skip, 84.926ms | `app-idempotency.log` |
| Lead /root | 앱 `npm run typecheck`, `npm test`, `npm run build:web`, `npm run test:hosting` | 각각 exit 0. unit 114 pass, 0 fail/skip, 1.129s. hosting 16 route 및 manifest/service worker 검사. | `app-typecheck.log`, `app-unit.log`, `app-build.log`, `app-hosting.log` |
| Lead /root | `npm run test:pwa -- ai-question-scope.spec.ts --reporter=json` | exit 0, 8 expected, 0 unexpected/flaky/skip, 9.906s | `app-pwa-narrow.json`, `app-pwa-narrow-artifacts/` |
| Lead /root | `npm run test:pwa -- --reporter=json` | exit 0, 90 expected, 0 unexpected/flaky/skip, 160.752s | `app-pwa-full.json` |
| QA /Reviewer | 양쪽 worktree `git diff --check` 및 hash 대조·XML/JSON 독립 집계 | exit 0, 모든 보존 target hash 일치 | 이 보고서 및 manifest |

PWA JSON에는 npm 명령 preamble이 있어 첫 JSON object 이후를 파싱했다. 전체 테스트의 세 skip은 두 `AiAnswerHistoryTests` PostgreSQL deletion/completion locking 사례와 한 `ScriptureCopyControllerTests` PostgreSQL account-erasure locking 사례다.

## Review 지적과 해결

| 심각도 | 지적 | 상태 / 이유 |
| --- | --- | --- |
| MEDIUM | quality evaluation off에서 새 scope 비용이 생기는데 기존 reservation evaluator_model은 null | FIXED. 항상 현재 evaluator model 영속; 통합 회귀로 field·tokens·cost 확인. |
| MEDIUM | 원래 문맥으로만 해석 가능한 premium 후속 답변의 재생성은 null context 때문에 새로 거절됨 | ACCEPTED. CR-001/v1.1에 호환성 제한·사용자 흐름 영향 명시. 최신 문맥을 과거 질문에 추정 적용하는 위험을 피하고 원 답변 유지. [후속 Issue #158](https://github.com/ByeongDoo-Han/mypasture-backend/issues/158)로 당시 문맥 복원 추적. 새 context-only decline 회귀 통과. |
| MEDIUM | 모델 HTTP JSON 자체가 malformed이면 decoder 예외가 provider 오류 계약을 벗어날 수 있음 | FIXED. scope execute 주변 targeted catch는 cancellation·기존 provider 예외 보존, 다른 decode runtime을 provider로 변환; 새 provider 사례와 최종 전체 검사 통과. |
| LOW | same-ID 실패 재전송과 GET receipt의 오류 코드 차이 / 완료 historical replay | ACCEPTED. CR-001에서 기존 재전송 호환성과 scope exact status 의미 명시. 과거 반환 답변은 계속 조회될 수 있다는 요구 한계가 남는다. |
| SUGGESTION | mock 및 HTTP stub corpus는 실제 production classifier의 혼합 의도·종교어 장식·injection 정확도를 입증하지 않음 | RECORDED LIMITATION. 비용/권한을 검증한 production semantic corpus와 latency/cost 평가가 운영 단계에서 필요. 통과한 mock을 live 모델 정확도라고 보고하지 않는다. |

Architecture: application gate와 provider 의미 판단·구조화 검증 책임을 분리했고 기존 port/client/config/usage 구조를 재사용한다. 새 의존성·환경변수·DB migration 없음. 새로운 source ownership, query, lock, cache privacy 우회나 민감 입력 로그 추가는 발견하지 않았다. 구현자가 추가 수정하면 관련 target hash와 검사/리뷰를 갱신해야 한다.

## 잔여 완료 조건

- 최종 커밋·PR CI 원본 확인은 Lead 책임이며 아직 이 보고서의 로컬 통과만으로 완료하지 않는다.
- 서버 선행 배포 계약과 운영 자동 배포 승인을 유지한다. 이번 검토는 병합·배포·스토어 출시 완료 보고가 아니다.
- 유료 live classifier 정확도 검사는 미실행이며, 문맥 의존 재생성 제한과 과거 완료 답변 재조회 예외는 위와 같이 남는다.
