# #151 / #114 AI 기록 독립 QA

## Test Summary

담당자 `/root/history_independent_qa`. 구현 코드는 수정하지 않았으며 실제 서버·앱 diff, 새 파일, 기존 완료·피드백·탈퇴·SSE·HTTP client 코드를 검토했다. **최종 독립 QA: PASS / MERGE READY (기능·필수 로컬 검증 기준).** 최종 CI, 별도 Reviewer 판정과 운영 배포 승인·migration 순서 재확인은 Lead 병합 조건으로 남는다. 아래 초기 실패와 대기 기록은 발견·수정 과정의 근거이며 마지막 최종 서버 QA에서 해결 상태를 갱신했다.

검토 대상: 서버 HEAD `c44a96f153e7ea0d2b1d7f279e1a0844dcf3706e`, 앱 HEAD `7941fe94522ec612fbfd85454284321d75570b1e` 위 미커밋 변경. 2026-10-03 초기 검토 시 변경 경로명+내용 SHA-256은 서버 `5d235835d11bca1c04e4928ff64fcda3c486cde415913d465d4bcddd50ed3d8e`, 앱 `3269e7fa8385d0e872bcf9c146119e171fbe7226412cdf3276f9778c224c2924` (이 QA 문서는 해시에서 제외). 이후 수정은 최종 인수인계 기준으로 다시 확인한다.

## Passed

- 소스 확인: JWT 사용자 ID를 모든 history 조회·삭제에 전달하며 다른 사용자 상세는 존재 여부와 동일한 404다. 다른 사용자 DELETE는 204로 처리하고 원본을 변경하지 않는다.
- 소스 확인: 사용자·시각·turn ID와 HMAC을 포함한 opaque cursor, 길이 제한, 변조 및 타 계정 사용 거부가 있다.
- 소스 확인: 질문/전체 답변은 history 전용 AES-GCM 도메인 키로 암호화한다. 원본 및 개선본 답변의 생성 파이프라인/모델 설정/응답 스키마 버전을 저장한다.
- 소스 확인: history GET/DELETE 및 history 도메인 오류는 no-store를 설정한다. 앱 history query key는 계정별이며 apiFetch가 요청 전·응답 수신 후·본문 파싱 후 계정 변경을 검사한다.
- 소스 확인: SSE disconnect는 emitter 전송만 중단하며 서버 생성 완료 흐름은 계속된다. 미완료 응답이 history 저장 경로를 직접 호출하지 않는다. clientRequestId 재전송은 기존 완료 영수증을 반환한다.
- 직접 실행: 서버 `git diff --check` 종료 코드 0. 전체 테스트를 QA가 중복 재실행하지 않았다.
- 결과 원본 확인: `/private/tmp/mypasture-history-checks/app-unit.log` 90 tests / 90 pass / 0 fail / 0 skip, 1.108초. 실행 종료 코드와 최종 변경 대상 일치는 구현 담당자의 인수인계가 필요하다.
- 결과 원본 확인: 앱 web build 로그의 export 및 PWA 생성 결과. 런타임 브라우저 검증과는 구분한다.

## Failed

- 초기 서버 focused 로그 및 XML 확인: 총 6 tests / 1 failed, 45초, BUILD FAILED. `AiAnswerHistoryTests.completed answer stores encrypted question prayer citations and independent turn ids`의 `cached.cacheHit`가 false였다. 캐시 인프라와 분리하는 테스트 설정이 추가 중이며 수정 후 실제 재실행 결과로 갱신해야 한다.

## Edge Cases

소스에서 정상·잘못된 size·변조 cursor·다른 계정 cursor·다른 계정 상세/삭제·반복 삭제·원본과 첫 개선본·root 만료 후 개선본 동시 삭제를 검사하는 테스트가 확인됐다. 아직 해당 최종 실행 결과는 미확인이다. 동일 created_at 경계의 cursor 안정성, 저장 실패 rollback, 원본 삭제 후 개선본 생성, 다단계 개선본은 추가 검증 요청했다.

## Concurrency

- history 원본 행의 pessimistic lock을 개선본 저장·그룹 삭제·cleanup에서 공유하는 설계를 소스로 확인했다. 실제 PostgreSQL 삭제/완료·cleanup 경합 결과는 미실행/미확인이다.
- 계정 탈퇴는 user FOR UPDATE → receipt/history/usage 삭제, 완료는 usage FOR UPDATE → history INSERT의 user FK 잠금이다. 역순 경합의 deadlock 가능성을 Lead에 전달했다. 실제 재현 전에는 확정된 오류로 표시하지 않는다.

## Failure Scenarios

완료 저장 실패 시 usage/receipt/history가 같은 트랜잭션에서 rollback되는지 주입 실패 테스트와 PostgreSQL 근거를 요청했다. SSE 불완전 응답·완료 이후 네트워크 종료는 기존 복구 테스트 원본을 확인해야 한다. 앱 삭제 실패 재시도·다음 페이지 실패 재시도·화면 계정 전환은 코드 경로가 있으나 실제 UI 상호작용 근거는 아직 없다.

## Regression

기존 정책 gate는 사용자 ID 없는 query key 및 60초 staleTime을 사용한다. 정상 로그아웃은 cache를 지우지만 자동 401 로그아웃→다른 계정 로그인에서는 이전 동의 화면 상태를 재사용할 가능성이 있다. 서버 생성 API는 최신 동의를 검사하고 새 history API는 본인만 읽으므로 다른 계정 기록 노출로 단정하지 않는다. Lead가 연동 범위와 수정 여부를 결정해야 한다.

사용자가 생성일 기준 1년 보관을 확정했다는 Lead 전달을 받았다. root 질문 생성 기준으로 개선본 묶음을 삭제하는 구현과 정책 문구를 일치시키는 작업은 Lead 담당이며 이전 정책 대기 상태는 해제됐다.

## Bugs

### BUG-001 다단계 재생성 기록이 원본 그룹에서 분리됨

Severity: HIGH. 상태: Lead CR-003 수락, 구현 담당 수정 중, 최종 독립 재확인 필요.

Reproduction:
1. A 원본 답변을 받고 아쉬움 평가 후 B 개선본을 만든다.
2. B에도 아쉬움 평가 후 C 개선본을 만든다. 기존 피드백 API는 개선본 재평가·재생성을 허용한다.
3. A 및 C 상세를 조회하고 A를 삭제하거나 A 만료 cleanup을 실행한다.

Expected: A/B/C가 동일 원본 그룹의 버전으로 조회되고 그룹 삭제·만료 때 모두 제거된다.

Actual (수정 전 소스): historyWriter가 바로 전 usage 부모를 저장하므로 C.parent=B가 된다. 상세는 한 단계 부모만 root로 취급해 A 상세에 C가 누락되고 C 상세 root가 B가 된다. A 삭제는 A/B만 제거하여 C 암호화 원문이 고아 기록으로 남고 parent-null만 선택하는 cleanup에서도 제거되지 않는다.

Suspected Cause: history 그룹 부모와 usage의 직접 재생성 부모가 같은 의미로 사용됐다. history 저장 시 최상위 root로 정규화하는 CR-003과 A→B→C 상세/삭제/cleanup 회귀 검증이 필요하다.

## Remaining Verification

- 수정 후 focused 및 필수 서버 전체 검사 결과, 실제 PostgreSQL/Flyway·경합·rollback.
- 최종 앱 typecheck/unit/web/PWA 실행 종료 코드와 소스 대상 일치.
- 목록/더보기/상세 원본·개선본/기도문/말씀 이동/삭제 확인 및 실패/계정 전환 실제 화면 근거.
- 최종 정책 문구, migration 순서, final CI 및 독립 Reviewer 결과.

스토어 출시 및 운영 배포는 이 QA의 실행 항목이 아니며 배포 완료로 표시하지 않는다.

## 최종 앱 QA (2026-10-03)

**앱 범위 판정: PASS.** 이 단락 작성 당시 서버 최종 근거는 대기 상태였으며 이후 아래 최종 서버 QA로 해소했다.

독립 QA가 앱 `docs/tasks/114-ai-answer-history.md` 인수인계, 현재 tracked diff 및 새 파일을 확인했다. `/private/tmp/mypasture-history-checks/app-validation-manifest.json`의 9개 소스 파일 SHA-256을 현재 파일에서 직접 계산했고 **모두 MATCH**였다. base는 `7941fe94522ec612fbfd85454284321d75570b1e`이며 최종 검사 소스가 후속 변경 없이 보존됨을 확인했다. manifest의 명령·종료 코드와 원본 로그의 집계를 대조했고 검사 자체는 QA가 중복 실행하지 않았다.

| 검사 | 실행 담당 결과 | 독립 QA 근거 확인 |
| --- | --- | --- |
| `npm run typecheck` | exit 0 | manifest + `app-typecheck.log` |
| `npm test` | exit 0, 97 pass / 0 fail / 0 skip, 1640.904333ms | manifest + `app-unit.log` 실제 집계 |
| `npm run build:web` | exit 0 | manifest + `app-web-build.log` export/PWA 산출물 |
| `npm run test:pwa` | exit 0, 58 pass, 2.1m | manifest + `app-pwa.log`, history UI 6 시나리오 × desktop/mobile = 12개 |
| `git diff --check` | exit 0 | 인수인계/manifest, 최종 독립 diff 검사 추가 |

실제 UI 테스트 구현과 결과를 확인했다: opaque cursor 더보기와 다음 페이지 실패·재시도, 원본+여러 개선본·기도문·버전 표시, 삭제 취소/실패/재시도/즉시 목록 반영, 목록·상세 실패 재시도·빈 기록, A의 늦은 응답 후 B 로그인 격리, 생성된 답변의 정확한 버전 직접 열기, B의 최신 AI 동의 요구. 브라우저는 Chromium desktop/Pixel 7 viewport이며 API는 모의 응답이다.

CR-004 해결 확인: Gate/Center가 계정 ID별 query key와 key 기반 remount, gcTime 0, API 요청 전후 expected-user 검증을 사용한다. 동의 mutation 성공도 현재 계정을 재검사한다. History 조회·상세는 계정별 key/expected-user guard/gcTime 0이며 AI 화면이 해제되면 해당 계정 조회를 취소하고 제거한다. 원본과 개선본 삭제 직후 해당 root를 목록에서 제거하여 재조회 실패 때 삭제된 기록을 계속 표시하지 않는다.

`app-history-detail-desktop.png`, `app-history-detail-mobile.png`를 독립 QA가 직접 열어 원본/개선본 탭, 버전, 말씀과 기도문 및 모바일 본문을 확인했다. 캡처 범위에 가로 넘침이나 텍스트 겹침은 없다. 이 근거를 실제 네이티브 기기, 새 서버 API 또는 운영 데이터 검증으로 표현하지 않는다. 말씀 이동은 기존 navigation 파라미터를 사용하는 소스로 확인했으며 새 기록 화면에서 실제 성경 연결 UI 동작을 따로 실행한 근거는 없다. 서버 선행 계약 준비 후 연동 운영 확인은 별도 단계다.

새 미해결 앱 HIGH/BLOCKER는 발견하지 않았다. 초기 앱 90-test 집계는 현재 97-test 결과로 대체되며 서버 초기 실패·CR-003/005 검증은 서버 최종 단락에서 별도로 갱신한다.

## 최종 서버 QA 및 통합 판정 (2026-10-03)

**서버·앱 독립 QA: PASS. 미해결 BLOCKER/HIGH 없음. MERGE READY는 소스 및 필수 로컬 검증에 대한 판단이며 CI·Reviewer·배포 승인 확인을 대신하지 않는다.**

서버 base `c44a96f153e7ea0d2b1d7f279e1a0844dcf3706e` 위 최종 코드의 `/private/tmp/mypasture-history-checks/server-tested-source-manifest.json` 23개 파일 SHA-256을 독립 QA가 현재 파일에서 계산해 **모두 MATCH** 확인했다. 인수인계의 exit 0과 로그의 BUILD SUCCESSFUL, 각 실행별 보존 XML 원본을 대조했다. 마지막 full 실행 뒤에도 23개 파일이 동일했다. 전체 검사를 독립 QA가 중복 실행하지 않았다.

| 검사 | 실행 환경·결과 | 근거 |
| --- | --- | --- |
| history/controller/AI flow focused | H2, 28 tests = 26 pass / 0 fail / 2 PostgreSQL 전용 skip, exit 0, 49초 | `server-focused-worker-final.log`, `server-focused-worker-final-xml/` |
| history/controller PostgreSQL | 일회용 PostgreSQL 17.9, 13 tests = 13 pass / 0 fail / 0 skip, exit 0, 76초 | `server-postgres-worker-final.log`, `server-postgres-worker-final-xml/` |
| `./gradlew test --no-daemon` | H2 전체, 251 tests = 249 pass / 0 fail / 2 PostgreSQL 전용 skip, exit 0, 112초 | `server-all-worker-final.log`, `server-all-worker-final-xml/`의 52개 suite XML |
| `git diff --check` | 독립 QA 직접 실행 exit 0 | 최종 두 저장소 diff 검사 |

집계 원본은 `server-test-summary.json`이다. 독립 QA가 52개 full XML의 tests/skipped/failures/errors를 직접 합산해 251/2/0/0을 확인했다. full 로그는 `:test`가 실제 실행되었으며 UP-TO-DATE인 compile 작업을 테스트 재실행으로 세지 않았다. PostgreSQL XML에서 실제 JDBC 대상이 일회용 `127.0.0.1:15451/history_test`, PostgreSQL 17.9이고 Flyway 44 migration checksum validation 및 현재 버전 42.2임을 확인했다. 테스트 설정은 PG driver/Flyway enabled/Hibernate ddl-auto validate를 사용한다. PostgreSQL 전용 두 테스트는 H2의 skip을 별도 PG 실행에서 모두 수행했다.

### 버그 해결·실패 조건 검증

- **BUG-001 / CR-003 해결:** A→B→C 실제 재생성에서 history의 C.parent=A, usage의 C.parent=B를 구별하고 상세 3개·목록 개선본 2개·B 경유 그룹 삭제·삭제 뒤 C 재생성 기록 미생성을 검사한다. history root 정규화와 root 행 잠금 및 source 재확인 소스를 확인했다. 초기 고아 민감 기록 결함은 해결됐다.
- **CR-005/008 해결:** complete/fail은 잠금 없는 scalar userId 조회 뒤 활성 user → usage → history root/receipt 순으로 잠근다. PG 테스트는 completion이 user lock을 실제로 기다림을 `pg_blocking_pids`로 확인하고, 같은 시간 탈퇴 트랜잭션이 usage를 `FOR UPDATE NOWAIT`로 얻을 수 있음을 확인한다. 실제 AuthService 탈퇴 후 완료 실패·usage/history 삭제를 검사한다.
- **PG 첫 실패 assertion 보완 확인:** Spring transaction wrapper 아래 원인 사슬에서 `IllegalStateException`을 찾는 변경이다. 잠금 대기·NOWAIT·실제 탈퇴·최종 데이터 없음 assertion을 유지하며 단순히 임의 오류를 성공 처리하지 않는다. deadlock SQL 예외만으로는 통과하지 않는다.
- **CR-009 해결:** stale worker가 user ID 순서로 user → usage → receipt를 잠그고 usage가 PENDING인 경우에만 복구한다. PG에서 worker의 실제 user lock 대기 뒤 다른 트랜잭션이 완료/탈퇴하는 두 분기를 검증하고, 완료 결과의 usage/receipt/history 보존 또는 탈퇴 뒤 삭제를 검사한다. worker/완료가 같은 사용자의 최종 상태를 덮어쓰는 경로를 막는다.
- **원자성·중복:** 실제 완료+history flush 뒤 강제 transaction failure에서 usage PENDING/receipt PROCESSING/history 없음으로 rollback된다. 서로 다른 답변 동시 complete가 한 결과만 확정하고 영수증과 history 본문이 일치하며 늦은 complete/fail 및 삭제 뒤 재완료도 기록을 되살리지 않음을 검사한다.
- **삭제 경합:** root 잠금을 잡고 삭제하는 동안 개선본 완료가 진행되어도 최종 그룹이 비며 새 개선본 기록이 남지 않는다. 실제 PG 실행 근거를 확인했다.
- **보관기간:** root 생성 365일이 지난 항목은 worker 전에도 목록에서 제외·자식 ID 상세 404, group cleanup은 더 최근 개선본까지 삭제한다. 정책 문구는 원본 질문 생성일 기준 365일 및 개선본 묶음 삭제로 정리됐다. 물리 파기는 일일 worker 작업이며 장애 모니터링·재시도 운영 제한이 문서화돼 있다.
- **버전/캐시:** cache scope에 AI_PASTOR_VERSION을 추가해 이전 pipeline 답변을 새로운 pipeline 버전으로 기록하는 재사용을 막는다. 초기 cache-hit 실패는 테스트용 계정/질문/범위별 cache port로 Redis 가용성과 분리했으며 cacheHit/서로 다른 turn ID/중복 영수증 assertion을 제거하지 않았다.
- **SSE 불완전 전송:** 테스트가 실제 IOException output 실패 횟수 >0, runner 종료, request COMPLETED, 상세 원본 1개 및 동일 clientRequestId 재전송 후 목록 1개를 검사한다. 출력 연결 실패와 저장 완료의 정책을 확인했다.
- **권한·cursor·삭제·탈퇴·정책 회귀:** JWT 소유권/no-store, 잘못된 size/UUID/cursor, 다른 계정, 반복 삭제, 같은 시각 UUID 부호 경계 페이지 누락 없음, 탈퇴 시 history 원문 제거, 최신 AI 민감정보 동의 1.1.0 요구 및 기존 1.0.0 원문 읽기 검사 근거를 확인했다.

### 비해당·잔여 한계

새 비용·외부 AI provider·모델 품질 변경은 없음. AI mock/local generation과 일회용 DB의 행동 검사이며 실제 provider 실패율이나 운영 데이터 성능을 검증했다는 뜻이 아니다. 클라이언트는 Chromium desktop/mobile viewport 모의 API 검사이며 실제 네이티브 기기·서버와 결합한 운영 검증·스토어 출시를 수행하지 않았다. GitHub 최종 CI 및 실제 적용 migration 순서·운영 승인 경계는 Lead가 별도로 판단한다. 구현 기능·필수 로컬 검증의 누락이나 미해결 HIGH/BLOCKER는 없다.
