# 작업 계약: AI 후속 문맥·연속 필사·검사 안정화 (#158/#162)

## Requirement
사용자는 제안한 네 단계(기존 AI 범위 제한 운영 반영, 간헐 검사 안정화, 당시 상담 문맥 복원, 여러 절 연속 필사)를 `진행해줘`로 승인했다. 단계1의 기존 서버 PR159/앱 PR124는 검증된 head를 main에 병합했다. 기존 서버 공개 운영과 웹 앱 공개는 해당 제안 범위에서 진행한다. 스토어 출시·유료 기능·새 인프라·오프라인 개인데이터 저장·기억하며 쓰기는 제외한다.

## Existing System
server base 4880f8b356157b56b8c80db1f309fd7da8561b6c, app base fc95217 (실제 full SHA는 git/PR 기록). 기존 dirty main-2 사용자 파일은 변경하지 않는다. 최신 main을 fetch한 새 worktree: server `next-steps-162`, app `continuous-copy-127`. 서버 Issue158 문맥, Issue156 SSE, Issue161 오류 assertion, Issue162 연속 필사; 앱 Issue127. 기존 AI scope gate/Responses adapter/JDBC usage/history/conversation 암호화·보유기간과 BibleReader/ScriptureCopyService의 user 행 잠금·revision·canonical snapshot, 앱 CopyInput/autosave queue/session guard/query/네비게이션을 재사용한다. 신규 라이브러리 없음.

## Impact
규모 Large: 서버 AI 완료 트랜잭션·개인 문맥·보유기간·삭제경합과 성경 필사 DB/API·앱 UI의 여러 기능 연동. 검증 위험 고위험: 개인정보/삭제/동시성/DB 변경이 있어 서버 담당→인접→전체 및 실제 PostgreSQL/Flyway 필수; 앱 담당·전체/type/build/hosting/PWA와 독립 QA·별도 Review 필수. 검사 소스를 동결하고 같은 결과를 원본으로 재사용한다.

## Work Breakdown
| 기능 단위 | 역할/실제 Agent | Owner | 허용 경로 |
| --- | --- | --- | --- |
| 계약·통합·필사 프론트·운영 반영 | Lead/Frontend/Infra /root | Scripture Intelligence; Foundation 공유 조정 | 두 docs/tasks 및 문서, 서버 migration/http/CI의 단일작성자; 앱 src/features/scriptureCopy/**, src/features/bible/BibleScreen.tsx, src/navigation/**, src/features/ai의 삭제 안내, 필요한 package test 목록 |
| 원래 상담 문맥 복원 전체 | Backend /root/question_scope_backend | Scripture Intelligence | 서버 ai/application/domain/infrastructure/presentation, 새 AiContextSnapshotTests 및 AiAnswerHistoryTests/관련 domain test. 기존 AiHistoryControllerTests/AiControllerStreamingTests와 shared migration/http/config/build 수정 금지 |
| 연속 필사·완료 묵상 전체 | Backend /root/scripture_backend | Scripture Intelligence | 서버 bible의 ScriptureCopy 관련 domain/application/infrastructure/presentation 및 ScriptureCopyControllerTests. shared migration/http/config 수정 금지 |
| 비결정적 검사 수정 | Implementation /root/test_stability | Scripture Intelligence | 서버 AiHistoryControllerTests.kt, AiControllerStreamingTests.kt 및 새 test-local helper/test만. 실행 제품/API/timeout 정책 수정 금지 |
| 실패·화면·회귀 검증 | QA /root/scripture_qa | Scripture Intelligence | 읽기 전용 제품 검증, 앱 tests/pwa/scripture-copy.spec.ts, scripture-copy-range.spec.ts, ai-history.spec.ts에 검증 작성 및 QA 보고서. 제품 코드 수정 금지 |
| 독립 설계·보안·정합성 검토 | Reviewer /root/scripture_review | Scripture Intelligence | 실제 통합 diff/코드/원본 검사 읽기, Review 보고서만 작성. 제품 수정 금지 |

실제 동시 슬롯에 맞춰 구현자가 끝난 후 QA/Reviewer를 실행한다. QA와 Reviewer는 다른 Agent이며 구현자가 담당한 부분의 독립 검증을 겸하지 않는다. 공유 파일과 기록은 root만 작성하고 각 Agent는 인수인계를 메시지/임시 보고서로 전달한다.

## Contracts
v1 확정 /root, 기능별 계약을 관련 Agent에게 전달.

### AI original-context v1
외부 API 요청/응답·SSE·오류 envelope 변경 없음. 원 질문 해시·원 답변/usage 소유권 검증 후 source history에 보관된 당시 입력 문맥만 scope/RAG/generation에 전달한다. 최신 conversation 내용으로 과거 문맥을 추정하지 않는다. legacy snapshot 없는 질문은 기존 standalone gate를 유지하며 짧은 질문은422 AI_QUESTION_SCOPE_UNCLEAR, 이전 답변 유지. 새 snapshot은 질문 직전에 소유권 확인하여 불변으로 load한 context이며 답변 생성 후의 context가 아니다.

DB history에 nullable encrypted snapshot과 고정 expiresAt(최초 capturedAt+30일) 추가. 기존 history365일 정책과 구분하며 재생성으로 만료 연장 금지. 기존 암호화 codec/키 사용, 제한된 최근 turns만 저장. API/로그/receipt/cache에 snapshot 내용·암호문 노출 금지. 만료 snapshot은 기존 retention 경로에서 파기, 계정 탈퇴시 history와 함께 물리 파기. 기존 데이터는 nullable이며 원래 문맥 backfill을 추정하지 않는다.

어떤 본인 AI history를 삭제하든 선택한 답변 group만 삭제하고 해당 사용자의 모든 복원 snapshot을 null화하며 현재 conversation 문맥도 물리 삭제한다. API/docs/앱 삭제 안내에 현재 상담 문맥 초기화를 명시한다. 다른 history의 답변 본문은 그대로 유지한다.

durable fencing: 완료·history/receipt/usage·conversation append를 동일 transaction에서 active user row lock 뒤 처리한다. captured conversationId가 같은 사용자 소유로 여전히 존재하는지만 확인하며 최신 텍스트는 사용하지 않는다. CLOSED는 역사 문맥의 존재로 인정, 일반 append는 ACTIVE 기준 유지. regeneration은 load한 source history의 snapshot 암호문/만료 값이 완료 lock 안에서도 동일하고 유효한지 확인한다. history삭제도 같은 active user lock을 먼저 획득해 snapshot 무효화와 conversation 삭제를 원자적으로 수행한다. 삭제/만료/source 변경이 먼저 완료됐다면 이후 in-flight 완료는422 SCOPE_UNCLEAR로 rollback하고 알려진 AI 비용은 기존 failure 경로에 기록한다. 프로세스/Redis lock만으로 삭제 후 부활 방지를 주장하지 않는다.

### Scripture passage v1
기존 PUT/GET/list/DELETE `/api/v1/scripture-copies`와 한 절 계약 유지. PUT에 optional `endVerse` 양수 추가, 기존 `verse`는 시작 절. 신규 omitted→한 절; 기존 omitted→저장된 끝 절 유지. 기존 version/book/chapter/start/end는 불변, explicit 다른 범위는409. 동일 장 최대20절, end>=start, canonical 각 절의 존재와 nonblank 검사 후 newline joining해 sourceText 저장. 범위 누락/빈 본문은404 BIBLE_NOT_FOUND, 잘못된/20절 초과 범위는400 INVALID_REQUEST. joined sourceText UTF16<=10000, 초과400; typedText/reflection의 기존 limit 유지. 원문은 server snapshot만 사용하며 완료는 전체 실질 문자 비교, 문장부호/공백/줄바꿈 무시.

응답은 기존 field + non-null endVerse(기존 한 절은 verse와 동일). DB scripture_copies end_verse 추가, 기존 rows=verse backfill, NOTNULL/positive/end>=verse/end-verse<20 constraint. 새 PATCH `/{id}/reflection` `{reflection(max1000), expectedRevision(>=1)}`→동일 record response. COMPLETED만 가능(DRAFT는409); 본문/위치/완료 상태 불변. 같은 묵상 재시도는 stale revision도 기존 revision 반환, 다른 묵상 stale409. user lock→record/revision 순서, 타인404, 무인증401, 삭제/탈퇴와 동일 잠금 순서. 기존 completed PUT 불변 유지.

앱은 Bible의 선택 절에서 같은 장 끝 절을 선택하며 한 절 기본 유지. 범위 시작 시 빈 DRAFT를 먼저PUT하고 응답 endVerse가 요청과 일치한 뒤 editor 표시(old server unknown field 무시를 fail-closed). 기존 회색 원문/검정 typing overlay·IME·700ms 저장 queue·입력 보존·409 명시적 불러오기·세션 격리 유지. 전체 range title/진행률과 기록ID 기반 재개. 완료 후 묵상만 별도 편집/저장/충돌·재시도 흐름. 원문 기억모드나 자동 완독/보상 없음.

### Test stability v1
Issue161: 오류 계약 code/message/path/fieldErrors 기준으로 cursor 비노출을 검증하고 무작위 traceId의 우연한 `bad`를 실패로 취급하지 않는다. traceId 충돌 재현 가능한 regression 필요. Issue156: MVC async 반환과 SSE 종료/event/runner 완료를 분리해서 bounded 기다림을 구현하고 실제 오류 code·done/usage/citation/receipt 검증 유지. 단순 sleep/skip/검증 삭제 금지. 운영 timeout 변경 없음, 누락 event/미완료는 반드시 실패.

### Shared schema / config
migration root 단일작성, 후보 V42.4 AI snapshot / V42.5 end_verse. 운영 migration 최근 로그에서 public42.3/up-to-date 확인, main 최신42.3, 열린 billing PR135의43/44를 확인했다. 배포 직전 실제 적용순서 재확인. 기존 migration 수정/out-of-order 금지. Event/new 환경변수/new dependency 없음. 서버 배포가 새 앱 공개보다 선행. 기존 단계1 앱 안내 공개도 scope 서버가 정상인 뒤 수행. 실제 모델 평가는 기존 live-test 경로, synthetic 질문의 좁은 표본과 평가 모델만 사용, 개인정보/키 출력 없음, 일반 CI는 실제 API 호출하지 않음.

## Dependencies / Risks
단계1 기존 CI 성공 head 병합→자동 server CI/deploy→운영 scope 확인. v1 계약→세 구현 slice 병렬→root shared migration/http 및 앱→담당/인접→동결 전체/PG/PWA→독립 QA와 Review→수정 영향 재검증→최종 PR CI→main 병합/server 배포→같은 main 기반 앱 웹 빌드/공개→운영 smoke. scope model 실평가는 semantic 정확도 표본이며 전체 정확도 증명 아님. snapshot 삭제경합·만료·타인 문맥, old client unknown range, async timing, 여러기기 revision과 IME를 우선 검증한다.

## Definition of Done
- [ ] 기존 scope 정책 main/운영 서버/웹 앱 반영 및 실제 표본 검증
- [x] 테스트 간헐 실패 두 종류의 회귀/실패 검증
- [x] owned original-context 복원과 만료/삭제/탈퇴/in-flight fencing
- [x] 1~20절 범위 작성/저장/재개/완료 후 묵상과 기존 한 절 호환
- [x] API/OpenAPI/http/DB/앱/운영 문서 일치
- [x] 필수 local 전체/실제PG/PWA와 독립 QA/별도 Review
- [ ] 최종 CI/main/서버·웹 운영 확인, 미실행·잔여 사항 구분

## 검증 계획
Root가 유일한 서버 전체/PG 실행 담당자이며 Agent별 좁은 검사 후 전체 동결본에서 실행한다. 각 결과는 커밋 또는 파일hash/diff·명령·환경·exit·확인가능 count·time·원본 위치를 기록한다. QA는 원본 확인과 직접 브라우저 실행을 구분하고 Reviewer는 actual diff/실패조건을 별도 판단한다. 실제PG는 Flyway42.4/42.5 clean 및 기존42.3 upgrade, snapshot delete/complete 경합·필사 concurrent/PATCH/delete/탈퇴를 포함. 앱 typecheck/unit/build/hosting/full PWA desktop/mobile, root screenshot 직접 확인. native OS IME/스토어 flow는 기기 없이 웹 검사로 대체 통과 주장하지 않는다. 모든 diff --check. CI trigger/실패우회 없음.

## Change Request CR-001 — 이전 revision의 삭제도 문맥을 파기
Current: 새 앱 코드에서만 user lock·snapshot 파기를 수행하면 롤링 중 이전 API의 DELETE가 새 snapshot을 남길 수 있다. feature flag 두 단계와 drain 대안은 진행 중 이전 요청의 완료를 확실히 증명하기 어렵다.

Proposed / 수락 root: V42.4에 PG AFTER DELETE STATEMENT/OLD TABLE trigger를 추가한다. 실제 삭제된 user IDs 전체를 UUID 순서로 먼저 FOR UPDATE NOWAIT로 잠그고 남은 snapshots null화와 conversation 삭제를 같은 transaction에서 수행한다. 0-row DELETE는 무효화하지 않는다. parent CASCADE로 user가 없어도 파기를 계속 수행한다. 모든 예외는 삭제 전체를 rollback하며 절대로 파기를 생략해 삭제만 성공시키지 않는다. 기존 완료/원 질문 root lock은 유지한다.

Reason / Impact: 사용자 lock을 기다리는 trigger는 legacy H→U와 새 U→H 사이 교착을 만들 수 있어 Reviewer의 HIGH 지적을 수락해 NOWAIT를 선택했다. 이 trigger는 잠금 단계의 기다림을 줄이며 모든 legacy 교착을 없앤다는 뜻은 아니다. 이전 multiuser 정리의 정렬 밖 history 잠금은40P01 가능성이 남는다.55P03/40P01은 미완료 삭제/정리 전체 rollback이며 기존 삭제 UI 재시도 또는 다음 정리 주기로 복구한다. 새로운 cleanup은 전체 처리 대상user를 전역 UUID 순서로 먼저 잠근다. 운영 로그에 transient 실패를 확인하고 지속하면 후속 조사한다. 신규 삭제 코드에서는U를이미같은TX에서보유하므로정상파기가능하다.

Compatibility / Verification: nullable schema/기존 데이터/외부 API/키/환경변수 유지. PG16에서 legacy direct DELETE, source복원fence, emptydelete, cascade, NOWAIT rollback→retry, multiuser overlap을 실제 검증한다. 함수는 invoker 권한이며 privilege 확장 없음. SQL·CI selector는 root 단일작성, QA·Reviewer에 별도 확인한다.

## Change Request CR-002 — 앱 기록 상태의 재사용 방지
Current: 같은 네비게이션 화면에서새필사/다른record로이동할때created상태와draftUUID가남을수있다. 시작응답유실뒤범위를바꾸면같은UUID의immutable범위와충돌할수있다.

Proposed / 수락 root: Bible에서새intent UUID를 전달하고 editor를recordID/intent별로remount한다. 첫 시작 후 범위를잠그고 같은UUID/range로만재시도한다. 범위변경은뒤로돌아가새필사시작. API는 응답range/record identity를검증한다. 저장응답을잃어도현재입력을유지하고서버의멱등응답을사용한다.

AI history삭제 성공은 현재client conversation/turn을초기화하되작성중form텍스트는보존한다. 현재ask/regen처리중에는삭제를비활성화해삭제후늦은응답이현재문맥을다시연결하는UI경합을줄인다. 서버fencing은다른client/네트워크경합을독립보호한다. API·웹캐시정책변경없음. QA의실제네비게이션/응답유실/묵상/삭제회귀검증필수.

## References
[PostgreSQL16 transition trigger](https://www.postgresql.org/docs/16/sql-createtrigger.html), [PostgreSQL16 locking SELECT](https://www.postgresql.org/docs/16/sql-select.html), [OpenAI structured outputs](https://developers.openai.com/api/docs/guides/structured-outputs). Existing Responses strict schema and explicit validated/uncertain business gate are retained; opt-in synthetic samples do not prove overall semantic accuracy.

### Rolling revision 경계

V42.4는 구버전 DELETE에도 새 복원 snapshot과 현재 conversation을 파기합니다. 구버전의 완료 use case 자체는 신규 user fence가 없으므로 삭제 전 append된 응답이 삭제 이후 옛 답변 기록으로 저장되는 기존 행동까지 차단하지는 않습니다. 그 기록에는 복원 snapshot이 없습니다. 새 revision의 완료는 source/conversation 존재를 같은 잠금·transaction에서 재확인하여 삭제 이후 새 복원 문맥을 생성하지 않습니다. 구버전 삭제 잠금 충돌은 55P03/40P01로 transaction 전체 rollback 후 사용자 재시도 또는 다음 cleanup 주기로 복구하며 부분 성공으로 취급하지 않습니다.

## Change Request CR-003 — 문맥 없는 원본도 완료 경계 검증

Review HIGH를 수락했습니다. snapshot이 없는 첫 턴·legacy·만료 기록의 재생성도 원본 source/root ID, 소유권, 생성 시각을 보유하고 완료 transaction에서 존재와 동일 그룹을 재확인합니다. 복원 가능한 snapshot이 없으면 명시적 질문은 기존 standalone scope 검사를 유지하지만 source가 삭제된 뒤 완료되지는 않습니다. snapshot 복원 시에는 암호문·만료와 대화 존재도 추가로 검사합니다. cleanup은 user별 문맥 파기를 한 번 수행하고 이미 NULL인 snapshot 행은 다시 갱신하지 않습니다. API·schema·보유기간 변경은 없습니다. 삭제 이후 성공을 기대했던 옛 회귀 검사는 새 422 rollback 계약에 맞춥니다.

## 최종 로컬 검증 / 독립 검토

대상 source381파일 manifest `2a48272e0a8b8a4d7dcb3358880774064ad2a75b7bf4b9d9dd3fcd43e53ef601`. 실행 담당 root. JDK17 toolchain/Gradle9.7.1, PostgreSQL16 pgvector Docker의 폐기 가능한 DB, 앱 Node26.5.1/Chromium desktop·mobile입니다. 최종 CI는 별도이며 Node22 환경을 검증합니다. 원본 로그/XML/JSON은 `/private/tmp/mypasture-next-steps-20261007/`에 보존합니다.

| 검사 | 실제 결과 | 근거 |
| --- | --- | --- |
| 첫 담당·인접 선택 |81개/1실패/4PG skip, exit1/58초. 유일실패는 새 롤백 테스트의 Spring exception wrapper 기대값. 테스트만 수정, 제품 불변 | backend-narrow-first-xml/backend-narrow.log |
| 수정된 문맥 담당 |14개/13통과/0실패/1PG skip, exit0/29초 | backend-context-fixed-xml/log |
| 실제 PG 담당·삭제guard·필사 |50개 전부 통과/skip0, exit0/46초.6개 guard는 clean/42.3 upgrade/제약/zero-row/cascade/NOWAIT bulk rollback/40P01 실제 경합 포함 | backend-postgres-xml/log/counts.json |
| 서버 전체 |313개/303통과/실패0/PG전용skip10, exit0/110초. skip10은 동일 테스트명이 별도 PG에서 모두통과한 것을 QA·Reviewer 대조 | backend-full-xml/log/counts.json |
| 실제 scope 평가 |합성7표본/실패0, exit0/24초. evaluator gpt-5.6-luna, 3826 input/159 output tokens, 추정 $0.000956. 전체 의미 정확도 증명은 아님 | ai-scope-live-xml/log |
| 앱 전체/type/build/hosting |unit119통과/실패0/skip0, typecheck/build/hosting exit0 | app-*-final.log |
| 앱 PWA |담당48통과/98.321초, 전체110통과/182.944초, 캡처만 안정화 후 해당2통과/4.631초; 실패/skip/flaky0 | qa-pwa-*.json |

별도 QA scripture_qa는 직접 PWA 실행·화면 확인과 서버 원본/대상 해시 확인을 수행했습니다. Reviewer scripture_review는 실제 diff/암호화/삭제·동시성/SQL/API/앱 상태/검사 충분성과 원본 결과를 독립 확인했고 서버·앱 MERGE READY, 미해결 BLOCKER/HIGH0입니다. root는 대표 mobile20절 작성 및 desktop완료묵상 화면을 직접 열어 확인했습니다. 실제 네이티브 기기 IME/스토어 검사는 미실행입니다. 제품코드 변경 없이 같은 대상의 통과 결과를 재사용했고 PostgreSQL과 일반H2 환경 결과는 구분합니다.

단계1 서버 main4880f8b의 CI37635651847/deploy37637538626 성공, API revision00046-9bl traffic100% 및 public readinessUP를 확인했습니다. 앱 기존scope변경은 app mainfc95217에 병합됐으며 이번 appPR128의 웹 공개에 함께 반영됩니다. 서버 새feature CI/main/deploy 및 앱 웹 공개는 아직 최종 조건입니다.
