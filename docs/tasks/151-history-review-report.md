# #151 / #114 AI 기록 독립 Review

## Review Summary

담당자 `/root/history_architecture_review`. 서버 및 앱의 실제 dirty diff, 신규 파일, 기존 생성·영수증·피드백·탈퇴·정책·공통 HTTP client 코드를 읽었다. 구현 코드는 수정하지 않았다. 2026-10-03 최종 소스 수정 후 재검토했다. 구현 코드의 미해결 BLOCKER/HIGH는 발견하지 않았으며 필수 서버·앱 최종 검증 원본 및 코드 해시 일치를 확인했으며 판정은 **MERGE READY**다.

계약 `docs/tasks/151-ai-answer-history.md` v1.3, CR-003~009와 사용자 확정 원본 생성일 기준 365일 보관을 기준으로 검토한다. final commit/CI 및 실제 운영 배포는 이 문서의 현 시점 확인 대상이 아니다.

## BLOCKER

미해결 없음. 필수 로컬 PostgreSQL/Flyway·전체 서버 및 앱 검증 원본과 대상 코드 해시 일치를 확인했다. 초기 실패는 수정 후 재실행 통과했으며 남은 실패로 취급하지 않는다. 최종 원격 CI 및 운영 배포 승인은 별도 단계다.

## HIGH

미해결 없음.

- REV-001 해결 확인: 잠금 전 사용자 식별은 `select usage.userId` scalar projection으로 바꿔 오래된 PENDING entity를 적재하지 않는다. 실제 usage는 user lock 이후 조회·잠근다. 최초 완료의 영수증과 history 본문이 일치하는 서로 다른 payload 동시 완료 검사 및 나중 완료·실패가 확정 결과를 변경하지 않는 검사가 추가됐다. 최종 PG 실행 원본에서도 통과를 확인했다.

## MEDIUM

미해결 없음.

- REV-002 해결 확인 / CR-008: fail도 user → usage → receipt 순서로 잠그며 이미 탈퇴·삭제된 사용자는 no-op이다. PENDING만 실패로 변경해 성공 후 후처리 오류나 늦은 실패가 COMPLETED usage/영수증을 덮어쓰지 않는다. 기존 역순 잠금은 제거됐다.
- REV-003 해결 확인 / CR-007: 목록·상세·개선본 저장이 원본 생성일 기준 365일 경계를 함께 적용한다. cleanup 전에 물리 데이터가 남아 있어도 목록·상세에서 보이지 않고 새 개선 history도 남지 않는다. 운영 문서에 worker 일일 파기 지연 및 잔류/실패 점검을 기록했다. 실제 운영 worker 파기 동작은 이번 구현 범위에서 실행하지 않았으며 배포 검증 항목이다.
- CR-006 해결 확인: 모델 설정/상호작용/응답 schema namespace에 `AI_PASTOR_VERSION`을 추가해 과거 파이프라인 cache 응답이 현재 생성 버전으로 기록되는 위험을 막는다. 새 namespace는 기존 캐시와 호환을 깨지 않고 이전 캐시를 조회하지 않는 방식이다.

- CR-009 / Issue #152 해결 확인: stale worker는 candidate receipt를 읽은 뒤 사용자 UUID 순으로 정렬하고 user → usage → receipt 순서로 잠근다. 잠금 전 읽은 receipt PENDING 값을 최종 상태로 믿지 않고 잠금 후 usage의 PENDING 상태를 우선한다. 완료 또는 탈퇴가 user lock을 먼저 얻으면 worker는 이를 기다린 뒤 completed usage 또는 삭제된 사용자를 건너뛰므로 확정 영수증만 FAILED로 바꾸는 경로를 막는다. 실제 PG 경합 검사는 pg_blocking_pids로 worker가 user 행에 대기하는 것을 확인한 뒤 완료/탈퇴를 확정하며 두 경로 모두 통과했다.

## LOW

없음.

## SUGGESTION

없음. 요청과 무관한 리팩터링·새 인프라·라이브러리를 권고하지 않는다.

## Architecture

원본과 다단계 개선본의 history root ID를 정규화하고 usage의 직접 부모 의미는 유지한다. DTO와 API interface를 분리하고 Entity를 응답으로 노출하지 않는다. 완료·영수증·history는 한 트랜잭션에서 확정하며 PK가 동일 turn의 중복 저장을 제한한다. root 행 잠금을 저장·삭제·cleanup에서 공유하여 삭제한 기록이 개선본으로 되살아나는 경로를 막는다. 완료·실패·stale worker의 user 선잠금과 terminal guard를 함께 적용했고 최초 완료 결과 불변의 경합 근거를 확인했다.

## Performance

목록은 size 1..50의 keyset cursor이고 `(user_id,created_at DESC,turn_id DESC)` partial index를 사용한다. 개선본 수는 목록 전체 root ID에 대한 한 번의 배치 GROUP BY라 N+1이 없다. parent 조회 및 전체 사용자와 분리된 expiry partial index가 있다. 상세는 root와 해당 개선본 조회 후 복호화하며 연관 entity lazy traversal이 없다. 하루 500 root 배치 cleanup 및 개선본 group 삭제는 실제 PG 검사에서 확인했다. 실제 운영 배치 실행·실패 점검은 배포 단계에서 확인한다.

## Security

질문·전체 답변은 무작위 96-bit IV/128-bit tag의 AES-GCM으로 암호화하며 기존 secret에서 history 전용 도메인 키를 파생한다. 운영 secret은 application-prod에서 필수 설정이다. plaintext preview 컬럼은 없고 목록 표시 시 필요한 범위만 복호화한다. JWT principal의 사용자 ID로 목록·상세·삭제를 제한한다. 타인 상세는 404, 타인/반복 삭제는 204이며 기록은 변경하지 않는다. 성공·도메인/파라미터 오류 응답은 no-store이고 HMAC cursor가 사용자·시각·turn ID에 묶인다.

앱 history와 정책 동의 key는 계정별이며 공통 client는 요청 전·응답 수신 후·본문 파싱 후 시작 계정을 확인한다. 계정 변경 시 화면 remount 및 query 취소/제거로 민감 UI 상태를 초기화한다. history query는 staleTime/gcTime 0이고 새 영구 저장 경로를 추가하지 않는다. 새 AI 민감정보 동의 1.1.0은 서버 generation API에서 확인하며 이전 정책 원문은 보존한다.

## Test Coverage

검사 재실행 담당자는 구현/QA이며 Reviewer는 원본을 읽고 중복 전체 실행을 하지 않는다.

앱 최종 대상: base `7941fe94522ec612fbfd85454284321d75570b1e` 위 미커밋 수정. `/private/tmp/mypasture-history-checks/app-validation-manifest.json`의 9개 코드/테스트 파일 SHA-256을 Reviewer가 직접 계산해 모두 일치함을 확인했다. manifest의 exit 0 및 로그 원본으로 typecheck, unit 97 pass / 0 fail / 0 skip(1.641초), web build, PWA 58 pass / 0 fail(2.1분)을 확인했다. history UI 12개 desktop/mobile 검사가 전체 PWA 실행에 포함됐다. 원본·개선본·기도문, 페이지/상세 오류·삭제 확인/실패/재시도, 늦은 A 응답 후 B 로그인, B 최신 AI 동의 검사가 있다. `app-history-detail-desktop.png`와 `app-history-detail-mobile.png`를 직접 열어 정렬·가독성·버전 선택을 확인했다. mock API 상호작용이며 실제 운영 API 또는 native binary 검사로 표현하지 않는다.

서버 최종 대상: base `c44a96f153e7ea0d2b1d7f279e1a0844dcf3706e` 위 미커밋 변경. `/private/tmp/mypasture-history-checks/server-tested-source-manifest.json`의 실행 영향 23개 코드/설정/테스트 파일 SHA-256을 Reviewer가 직접 계산해 모두 현재 소스와 일치함을 확인했다. 마지막 수정인 CR-009 worker 경합 보완도 포함된다. 일반 문서 및 docs/tasks는 실행 코드 해시에서 구분하며 계약·환경·정책 문서를 직접 읽었다. docs/tasks 제외 전체 30개 변경 파일의 경로·내용 hash는 `a6d023fa90042ecb99e30bf1bc2b22b4db4f1f09a6fc37f4d84f7bfac3252f6c`다.

실행 담당자가 세 명령 모두 exit 0으로 인수인계했으며 `server-test-summary.json` 집계와 다음 원본 로그/XML을 확인했다. Reviewer가 테스트를 직접 재실행한 결과가 아니라 실행 담당자의 원본을 독립 확인한 근거다.

| 최종 검사 | 결과 | 환경 / 시간 / 원본 |
| --- | --- | --- |
| focused history/controller + 기존 AI flow | 28 tests, 26 pass, 0 fail/error, 2 PG-only skip | H2 / 49초 / `server-focused-worker-final.log`, `server-focused-worker-final-xml/` |
| `./gradlew test --no-daemon` 전체 | 251 tests, 249 pass, 0 fail/error, 2 PG-only skip | H2 / 112초 / `server-all-worker-final.log`, `server-all-worker-final-xml/` (52 suite) |
| 실제 PostgreSQL history/controller | 13 tests, 13 pass, 0 fail/error/skip | 일회용 PostgreSQL 17.9 / 76초 / `server-postgres-worker-final.log`, `server-postgres-worker-final-xml/` |

모든 원본 위치는 `/private/tmp/mypasture-history-checks/`다. PG 실행은 V42.2 Flyway 적용과 Hibernate schema validate, 최초 완료·receipt·history 원자성 rollback, 서로 다른 응답 경합/late failure terminal 상태, 다단계 root/삭제·365일 조회 제한/cleanup, 완료·worker·탈퇴의 실제 행 잠금 대기와 최종 상태를 검사했다. H2에서 건너뛴 PG-only 두 검사는 실제 PG 실행에서 통과했다. SSE disconnect·clientRequestId 복구는 기존 AI flow 및 전체 회귀를 확인했다. 로컬 PG17.9와 CI workflow의 PG16은 구분하며 원격 CI 통과로 표현하지 않는다.

Reviewer 직접 실행: 두 저장소 `git diff --check` exit 0. 구현 코드는 수정하지 않았다.

## Final Review

**MERGE READY** — 최종 구현 소스와 필수 로컬 검증 근거를 확인했다. 미해결 BLOCKER/HIGH/MEDIUM은 없다. 이 판정은 최종 커밋 원격 CI 또는 실제 운영 배포 승인을 대체하지 않는다. 실행 코드가 변경되면 관련 검사와 리뷰를 갱신해야 한다.
