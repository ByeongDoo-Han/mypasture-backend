# Independent QA — server #164 / #165

담당 `scripture_qa`. 제품·migration·검사 파일은 수정하지 않았습니다. Lead `/root`가 작성한 실제 diff와 검사·실패 조건을 별도로 읽고, 단일 실행 담당 Lead의 원본 XML/집계 및 대상 해시를 독립 확인했습니다. 별도 Review는 `scripture_review`가 담당합니다.

**최종 로컬 QA PASS / MERGE READY.** 최초 full 실패는 통과 결과로 사용하지 않았으며 아래 고정된 최종 소스에서 담당·전체·실제 PostgreSQL이 모두 통과했습니다. 최신 커밋 CI·병합·운영42.6/ready 및 앱 공개는 Lead가 별도로 확인해야 합니다.

## 계약·대상

- base main `6de1632`, 별도 branch `fix/bible-164-legacy-insert`.
- 기존 적용 V42.5는 수정하지 않고 신규 `V42.6__legacy_scripture_copy_insert.sql`을 추가합니다. BEFORE INSERT에서 NEW.end_verse가 NULL인 경우에만 NEW.verse로 보정합니다.
- 명시적 범위와 다른 필드, 기존 데이터, UPDATE/DELETE를 변경하지 않습니다. 기존 NOT NULL/positive/end>=start/최대20절 제약을 유지합니다. 함수에는 다른 table 조회·추가 lock·SECURITY DEFINER·권한 확장이 없습니다.
- CI의 기존 실제 PostgreSQL 필사 검사에 신규 `ScriptureCopyLegacyInsertPostgresTests` selector를 추가했습니다. 기존 검사나 gate를 제거하지 않았습니다.
- #165/CR005는 진행 중 SSE response header를 기본 MockMvc 프린터가 읽는 경합에 대한 테스트 전용 import/annotation2줄 수정입니다. `@AutoConfigureMockMvc(print=MockMvcPrint.NONE)`이며 HTTP/SSE/body/error/done/usage/frame/runner 검증과 timeout 정책은 그대로입니다. 제품·운영 로그 설정을 변경하지 않습니다.
- 최종383파일 `legacy-copy-source.json` SHA256 `554a9dbab09e7de6cdf24c21b263f9b987e53903993fe8ab21f048b0dc1ec26f`. QA 원본 확인 시 모두 현재 파일 해시와 일치했습니다.

## 실제 실행 근거 — QA 재실행 없음

Root가 실행했으며 QA는 원본을 읽었습니다. 실제 PostgreSQL16 pgvector의 disposable DB, `AI_HISTORY_TEST_JDBC_*`와 `SCRIPTURE_COPY_TEST_JDBC_*` 환경을 사용했습니다. 인증 값은 기록하지 않습니다. 전체 원본 위치 `/private/tmp/mypasture-next-steps-20261007/`.

| 단계·명령 | 실제 결과 | 원본 |
| --- | --- | --- |
| 최초 담당 PG `./gradlew test --tests '*ScriptureCopyLegacyInsertPostgresTests' --tests '*ScriptureCopyControllerTests' --no-daemon` | Lead exit0/35초, QA XML2suites18pass0fail0error0skip | `legacy-copy-pg-xml/`, `legacy-copy-pg.log`, `legacy-copy-pg-counts.json` |
| 최초 full `./gradlew test --no-daemon` | QA XML315tests/1fail/0error/12skip. PASS로 사용하지 않음 | `legacy-copy-full-first-xml/` 및 최초 full log |
| 수정 SSE/helper `./gradlew test --tests '*AiControllerStreamingTests' --tests '*AiSseTestSupportTests' --no-daemon` | Lead actual exit0/32초, QA XML2suites13pass0fail0error0skip. 대상 AiControllerStreamingTests/AiSseTestSupportTests | `legacy-sse-fixed-xml/`, `.log`, counts JSON |
| 최종 full `./gradlew test --no-daemon` | Lead actual exit0/109초, QA XML60suites315tests303pass0fail0error12PGskip | `legacy-copy-full-fixed-xml/`, `.log`, counts JSON |
| 최신42.6 실제 PG `./gradlew test --tests '*AiContextSnapshot*' --tests '*AiContextDeletionGuardPostgresTests' --tests '*AiAnswerHistoryTests' --tests '*AiHistoryControllerTests' --tests '*ScriptureCopyControllerTests' --tests '*ScriptureCopyLegacyInsertPostgresTests' --no-daemon` | Lead actual exit0/45초, QA XML7suites52pass0fail0error0skip | `legacy-copy-postgres-all-xml/`, `.log`, counts JSON |
| QA `git diff --check` | actual exit0 | QA exec 원본 결과 |

최종 full의12개 skipped 모두 동일 classname+testname을 최신42.6 PG52 XML과 기계적으로 대조하여 실제 PASS임을 확인했습니다. 이전42.5 PG 결과를 최신42.6 검사로 표현하지 않습니다.

## 정상·실패·회귀 확인

- PostgreSQL42.5에서 구 binary의 end_verse를 생략한 INSERT가23502로 실패하는 것을 실제 재현했습니다. 같은 schema를42.6으로 upgrade한 뒤 동일 형태 INSERT가 한 절 end=start로 정상 저장됐습니다.
- 기존 range2–5와 원문·typedText·reflection·revision7·DRAFT 상태는 upgrade 뒤 보존됐습니다.
- omitted column과 explicit NULL의 fallback, explicit1–20 최대 범위 보존을 확인했습니다. end0/21은23514로 계속 거절합니다.
- clean latest schema에서 legacy 한 절7–7과 새 range10–12가 정상이며, 기존 row UPDATE end_verse=NULL은23502로 거절하고 기존 range를 보존했습니다. INSERT 외 작업에 보정이 적용되지 않음을 확인했습니다.
- 담당 controller16개도 실제 PostgreSQL에서 PASS이며 기존 범위/완료/묵상/권한/retry/동시성/삭제·탈퇴 behavior의 회귀 근거로 사용했습니다.
- 넓힌 PG52는 기존 AI snapshot 삭제 guard/동시성/계정 삭제/rollback 및 clean·upgrade 검사까지 같은42.6에서 검증했습니다.
- 최초 full 실패는 `stream scope error terminates without answer citation prayer or delta()`의 ConcurrentModificationException. QA가 확인한 첫 stack은 `PrintingResultHandler.getResponseHeaders` → `MockMvc.applyDefaultResultActions` → `perform`으로, 진행 중 진단 프린터 header iteration의 경합입니다. Lead의 print NONE 최소 수정에서 assertion/완료 검증이 제거되지 않았음을 실제 diff로 확인했고 담당13 및 전체315의 최종 결과로 재검증했습니다.

## 역할·재사용·미실행

- 앱 코드·lock·검사·실행 환경은 이 서버 보완에서 변경하지 않았으므로 앱 PR128/main 최종 CI 및 이전 dependency 갱신 환경 unit119/PWA110 원본을 재사용합니다. QA가 앱 검사나 빌드를 불필요하게 재실행하지 않았습니다.
- QA의 직접 실행은 read-only 원본/hash 확인과 diff check이며, 서버 테스트를 실행한 것처럼 표현하지 않습니다.
- 실제 이전 컨테이너 image의 전체 startup/서비스 traffic 전환, 운영 migration42.6 및 ready 확인은 Lead 담당입니다. QA가 확인한 rollback 쓰기 근거는 실제 PostgreSQL에 실행한 구 INSERT SQL입니다.
- 운영 배포·앱 웹 공개·최신 CI 완료는 이 로컬 QA 보고서만으로 완료라고 주장하지 않습니다.
