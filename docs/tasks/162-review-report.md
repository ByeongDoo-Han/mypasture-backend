# Independent Review — AI original context / continuous Scripture copy (#158 / #162 / APP-127)

## Reviewer and target
Reviewer: `/root/scripture_review`. 제품 코드·계약·migration·테스트 수정 없음; QA는 별도 `/root/scripture_qa`.
Server: `/Users/byeongdoohan/orca/workspaces/mypasture-server/next-steps-162`, base `4880f8b356157b56b8c80db1f309fd7da8561b6c`.
App: `/Users/byeongdoohan/orca/workspaces/mypasture-mobile/continuous-copy-127`, base `fc95217`.
실제 diff/관련 인증·AI 사용량·이력·암호화·conversation·탈퇴/GC·필사·앱 상태·API·내비게이션과 source 계약 v1/CR001/CR002를 읽고 독립 판단했습니다.

실제 구현 역할: Lead/Frontend/Infra 및 shared migration/guard 검사는 root, 원래 상담 문맥 Backend는 question_scope_backend, 연속 필사 Backend는 scripture_backend, 간헐 검사 안정화는 test_stability, 직접 browser QA는 scripture_qa. Reviewer는 어떤 구현 역할도 겸하지 않았습니다.

## Current verdict
Server: `MERGE READY`. App: `MERGE READY`(새 서버 API/migration 선행 배포 조건). 제품 코드 미해결 BLOCKER/HIGH 없음. 필수 서버 전체/실제 PostgreSQL 및 앱 전체 PWA 원본·대상 해시를 확인했습니다. App은 서버 검사와 별도로 PR/최종 CI를 준비할 수 있지만, 새 범위 API와 완료 묵상 PATCH 서버가 준비되기 전에 공개하지 않습니다. 이 판정은 최종 커밋 CI 통과, 실제 운영 배포·준비 상태/사용자 흐름 확인, native 실기기 검증 완료를 대신하지 않습니다. 초기에 실패한 검사나 마지막 성공 문구만으로 통과를 판단하지 않았습니다.

## Findings and resolution
| Severity | Finding / evidence | Resolution |
| --- | --- | --- |
| HIGH | 단순 AFTER DELETE user lock 대기는 legacy H→U와 새 완료 U→H 사이 교착. 기존 legacy delete는 SQL DELETE 전에 root SELECT FOR UPDATE까지 보유하므로 AFTER/BEFORE 변경만으로 순서 교정 불가. | CR001 수락: PG AFTER DELETE STATEMENT/OLD TABLE, DISTINCT 사용자 UUID 정렬로 모든 fence를 FOR UPDATE NOWAIT 획득한 후 snapshots pair NULL/conversation DELETE. 충돌을 삼키지 않고 삭제 TX 전체 rollback. actual SQL 확인. 성공 DELETE와 파기는 원자적이며 transient failure는 성공으로 표현하지 않음. |
| HIGH | snapshot 없는 legacy/첫 턴/만료 source는 restore null로 source fence가 빠져, standalone 질문 생성 중 원본 삭제가 커밋되어도 usage/feedback만 완료되고 history writer가 silent skip할 수 있음. | snapshot과 독립된 sourceCreatedAt/rootTurnId/rootCreatedAt identity 참조 도입. legacy도 참조를 load, 실제 source 없으면 generation 전422, 완료 lock 안 재확인. 존재/소유권/retention/source identity와 복원 snapshot ciphertext/expiry 확인. 최신 code 확인, 실제 PostgreSQL 및 최종 전체 원본으로 정상·삭제 선행·group 변경·rollback/비용 검증 통과 확인. |
| MEDIUM | retention batch에서 같은 user의 각 root마다 user 전체 history를 다시 UPDATE하며 이미 NULL인 rows도 rewrite. | GC는 user별 최초 실제 root에서 한 번 무효화, JPQL은 snapshot pair 중 non-null rows만 UPDATE. 모든 batch 대상 user를 UUID 순서로 선잠금. code 확인. |
| MEDIUM | 복수 legacy transaction의 여러 DELETE에 걸친 root H/U 순서는 transition 집합 정렬로 교정되지 않음. trigger 도달 전 cross-user history wait에서40P01 잔여 가능. | CR001/rolling limitation에55P03/40P01은 전체 rollback, 사용자 재시도/다음 GC주기 및 로그 관측을 명시. CyclicBarrier로 2user opposite DELETE의40P01 피해자 rollback/승자 commit/잔여 snapshots NULL/conversation0을 강제하는 실제PG 검사 코드 확인. 이 위험이 제거됐다고 주장하지 않음. |
| MEDIUM | legacy completion은 새 completion fence가 없어 old append→DELETE context 파기→old usage/history 완료의 기존 동작까지 delete trigger로 차단할 수 없음. | Lead가 rolling 보장범위를 구버전 DELETE의 새 snapshot/current context 파기로 한정하고 기존 답변 저장 가능성을 계약에 기록. 옛 기록에는 새 snapshot 없음. 새 revision 완료는 source/conversation existence를 같은 U transaction 안에서 재검증. 전체 legacy in-flight 완료 차단을 주장하지 않음. |
| LOW | API save에서 location으로 실제 record/selected 전체를 전달하면 공개 원문·id/status 등의 불필요 필드까지 전송. 서버가 무시하지만 계약·단위 검사와 실제 호출이 어긋남. | 앱 explicit version/bookCode/chapter/verse/endVerse DTO로 변경. actual diff와 source hash 확인. |
| LOW | 최초 범위 PUT의 ack 유실 후 동일 client UUID로 다른 범위를 선택하면 immutable 범위409. 여러 새 필사 의도가 이전 route state를 재사용할 수도 있음. | CR002: 최초 start 시 rangeLocked, 동일 payload/id 재시도, 새 intent UUID/navigation draftId/key로 독립 편집 상태. 실제 code 확인; focused browser의 retry/intent 검증 통과 근거 확인. |

## Architecture / privacy / consistency
AI snapshot은 원 질문 직전 소유 conversation을 불변 copy하여 최근 최대4 turns, 각 field bounds, schema version, capturedAt+30일 고정 expiry를 보존합니다. 생성 후/latest conversation으로 과거 문맥을 추정하거나 재생성으로 expiry를 늘리지 않습니다. source question hash/usage ownership 확인 후 scope/RAG/generation에 같은 원래 문맥을 전달합니다. snapshot 없음은 standalone scope를 유지하며 짧은 질문은422. AES-GCM의 기존 privacy secret에서 purpose key를 유도하고 owner+original turn AAD를 적용해 같은 사용자/다른 turn 및 타인 ciphertext transplant를 거부합니다. 외부 DTO/SSE/receipt/cache에는 snapshot을 넣지 않으며 snapshot 실패 로그에는 입력/암호문이 없습니다.

완료는 같은 active user U lock→usage/source/root 검증→conversation append→usage/history/receipt transaction으로 원자적입니다. append 이후 snapshot write 실패는 전체 rollback. deletion/retention은 U→H, multiuser cleanup은 전체 U를 전역 UUID 문자열 순서로 먼저 획득. contextState scalar 조회는 동일 JPA persistence context의 stale entity snapshot을 재사용하지 않습니다. nullable pair constraint/expiry partial index 및 source-owned root retention 검증 확인. 실제 탈퇴는 기존 erasure의 history/conversation 물리 파기를 재사용합니다.

V42.4 trigger는 삭제 rows의 distinct 사용자만 처리합니다. DELETE0 rows는 no-op; user FK cascade 뒤 user 조회0행이어도 문맥 파기는 계속 수행. AFTER STATEMENT에서 남은 같은 table UPDATE를 하므로 삭제 대상 row를 ROW trigger 중 다시 변경하는 위험을 줄이며 UPDATE 자체는 DELETE trigger 재귀를 만들지 않습니다. NOWAIT SQL 오류와 multiuser deadlock을 무시하지 않음. 함수는 invoker 권한이며 새 privilege/config/dependency 없음.

연속 필사는 optional endVerse에 대해 신규 omitted→한 절, 기존 omitted→저장 범위 유지. 같은 장 최대20절의 각 canonical verse 존재/nonblank와 joined source UTF16≤10000을 확인하고 immutable snapshot/위치/range를 보존. DB42.5는 기존 rows end_verse=verse backfill과 NOTNULL/range CHECK. 서버 완료는 전체 NFC L/M/N 비교. 완료 후 PATCH는 묵상만 변경하며 U lock/CAS revision/같은 묵상 stale retry를 적용, 본문/완료시각 불변. 타인404/인증401/수정충돌409 계약 확인.

앱은 빈 DRAFT PUT의 canonical endVerse 확인 후 editor를 표시하며 old server의 unknown endVerse 무시는 fail-closed. source permanent overlay/IME와 직렬 저장/세션 guard 유지. completion 이후 writer는 묵상 PATCH만 선택하며 최신 typing/reflection을 오래된 저장 응답으로 덮지 않습니다. 충돌·retry·명시적 reload·discard는 기존 회복 흐름 유지. AI history 삭제 안내는 현재 문맥 초기화/다른 답변 보존을 명시하며 pending ask/regeneration 중 삭제 UI를 막고 성공 시 conversation 상태를 초기화하되 unsent question은 보존합니다.

간헐 검사 수정은 제품 timeout/skip을 바꾸지 않습니다. cursor error의 code/message/path/fieldErrors/envelope와 유효 trace/timestamp를 검사하고 trace 안 bad substring의 deterministic collision/각 field 누출을 검증합니다. SSE는 완전한 terminal frame, MVC async 완료, runner idle을 같은15초 bounded deadline에서 확인하고 required delta/response/citations/usage/done 및 error code/done status를 유지. fake clock의 누락/미완료/timeout 실패 검사가 포함됩니다.

## Evidence inspected / pending
- App `app-final-source.json`116 files 및 `qa-app-target.json`58 files의 실제 SHA256 직접 비교: 모두 일치(현재 code freeze).
- `app-unit-final.log` 원본:119 tests/119 pass/0 fail/0 skipped/0 cancelled,1.265초. `app-typecheck-final.log`, `app-build-qa-frozen.log`, `app-hosting-qa-frozen.log`의 원본 및 Lead exit0 확인. 실제 export dist와 public hosting16 routes/5missing-path/service-worker/manifest 검사 결과 확인.
- `qa-pwa-focused.json` 원본 desktop/mobile:48 expected/0 unexpected/0 skipped/0 flaky,98.321초. QA 담당 실제 exit0 보고. `qa-pwa-full.json` 원본 desktop/mobile:110 expected/0 unexpected/0 skipped/0 flaky,182.944초, QA exit0. 필사20절의 원문 회색 suffix/검정 입력 mobile screenshot과 완료 후 묵상 desktop screenshot을 Reviewer 직접 확인. 캡처 전용으로 바뀐 ai-history browser test 추가 캡처 재실행은 별도 QA 갱신 대기이며 제품 소스는 그대로.
- 최초 서버 담당81개 실행은 exit1/유일1fail/4skip: snapshot-write failure의 실제 Spring InvalidDataAccessApiUsageException wrapper와 직접 IllegalArgumentException 기대 차이. test-only 수정은 wrapper+cause IllegalArgumentException과 기존 conversation turnCount/encryptedContext, usage PENDING, receipt PROCESSING, no history rollback assertions 모두 유지. 제품 소스 불변, AI14파일 최종 manifest `ba7d6877e196833d697e61b9f8428b226d2a6adc09a9f84c67b77d3515252b96` 실제 파일 해시 일치 직접 확인. 최초 실패를 통과 근거로 사용하지 않음. 통합 `backend-final-source.json`381파일 manifest SHA256 `2a48272e0a8b8a4d7dcb3358880774064ad2a75b7bf4b9d9dd3fcd43e53ef601`의 실제 파일 해시 모두 일치 직접 확인.
- 수정된 담당 context14개 실행: exit0/29초,13pass/1PG-only skipped(실제 PG 별도 실행). Root 실행, Reviewer는 수정 diff와 원본 근거 확인.
- `backend-postgres-counts.json` 및 `backend-postgres-xml`6suites의 원본:50tests/50pass/0fail/0error/0skip, Root exit0/46초. 실제 nullable snapshot/AAD/TTL/no extension/prior context topic mutation/delete/rollback/consumed cost/legacy source identity/root group 변경/동시 user lock, 필사 range/PATCH/CAS/탈퇴 검증 포함.
- Guard class6개 actualPG 전부통과: single NOWAIT55P03 rollback→retry, sorted second user lock 실패 bulk 전체rollback→retry, cross-owner 보존/zero-row no-op, opposite multiuser DELETE40P01 피해자 rollback/승자 commit, user cascade,42.3→42.5 upgrade/backfill/제약. XML system-out에서 clean47migrations→v42.5와 existing42.3 45migrations 이후2개 추가→v42.5 확인.
- `backend-full-counts.json` 및 `backend-full-xml`59suites 원본:313tests/303pass/0fail/0error/10PG-only skipped, Root exit0/110초. skipped testcase10개의 이름/사유를 직접 확인: guard6+snapshot1+history lock2+scripture lock1이며 동일 source의 dedicated PostgreSQL50개 실행에서 모두 actual통과. 오래된 보고서나 UP-TO-DATE 결과를 현재 통과로 재사용하지 않음.
- Reviewer는 담당 검사를 중복 실행하지 않고 원본+대상 해시를 독립 확인합니다. 앱 및 서버 git diff --check 직접 exit0. Reviewer 제품/검사 파일 수정 없음. 검토 후 기능 소스 추가 변경 없음; 보고서/캡처만 갱신.

## Limitations and release conditions
실제 iOS/Android OS IME/키보드/긴 범위 overlay/스토어 검사는 미실행. Browser mobile viewport·합성 composition을 native 실제검증으로 표현하지 않습니다. Live model synthetic narrow samples는 semantic 전체 정확도를 증명하지 않습니다.

기존 scope 단계의 운영 반영과 이번 후속 변경 배포는 구분해야 합니다. 최종 CI 성공, 최신 base/실제 적용 migration 순서42.3→42.4→42.5 확인, 승인된 서버 배포 후 준비/핵심 흐름 확인, 서버 선행 뒤 웹 공개가 별도 완료 조건입니다. Reviewer의 향후 MERGE READY는 CI나 실제 운영 배포 완료를 대신하지 않습니다.

## Primary references
AFTER transition relation/FK cascade·복수 transition set의 의미는 [PostgreSQL16 CREATE TRIGGER](https://www.postgresql.org/docs/16/sql-createtrigger.html)를 확인했습니다. 락을 transaction 종료까지 유지하고 상반된 획득 순서에서 교착할 수 있다는 기준은 [PostgreSQL16 Explicit Locking](https://www.postgresql.org/docs/16/explicit-locking.html)을 확인했습니다. 저장소의 구체적인 H→U/U→H 및 legacy multiuser 위험 판단은 실제 SQL/application 코드에 대한 Reviewer의 추론입니다.
