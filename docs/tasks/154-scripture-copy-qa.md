# Independent QA — MP-154 / APP-121

담당: scripture_qa. 제품 코드는 수정하지 않았습니다. 단일 허용 경로 `tests/pwa/scripture-copy.spec.ts`에 실제 앱을 실행하는 browser 행동검사 8개를 작성했습니다. 외부 로그인/API는 fixture로 격리하며 서버/DB 검사를 대체하지 않습니다.

## Target

- server base `fbad2e1`, app base `6fb9b6f`.
- 최종 browser 산출물과 변경파일: `qa-app-browser-final-target-sha256.json`의 60파일. 전체 실행 직전 생성, 완료 후 현재 해시 전부 일치.
- 서버 최종 대상: `backend-target-final-v423-sha256.json`; 원본 XML 확인 시 현재 해시 전부 일치. migration V42.3.

## 직접 실행

| 검사 | 명령/환경 | 결과 | 원본 |
| --- | --- | --- | --- |
| 최초 narrow | `npm run test:pwa -- tests/pwa/scripture-copy.spec.ts --reporter=json` Chromium Desktop Chrome / Pixel 7 | exit1, 12pass4fail0skip, 55.198s | qa-pwa-narrow.json/.log/artifacts |
| 수정 후 담당 회귀 | `npm run test:pwa -- tests/pwa/scripture-copy.spec.ts --grep 'persistent source\|save failure\|remotely deleted' --reporter=json` | exit0, 6pass0fail0skip, 10.664s; 이전 산출물의 진단검사로만 보관 | qa-pwa-targeted-final.json/.log/artifacts |
| 중단된 전체 | `npm run test:pwa -- --reporter=json` | root가 제품2줄 및 빌드를 변경하여 SIGINT exit130. 미완료/결과 불재사용 | qa-pwa-full-interrupted.json/.log/artifacts |
| 최종 전체 | `npm run test:pwa -- --reporter=json --output=/private/tmp/scripture-copy-checks/qa-pwa-full-final-artifacts` Chromium Desktop Chrome / Pixel 7 | exit0, 82pass0fail0skip0flaky, 146.301s. 신규 필사16 포함 | qa-pwa-full-final.json/.log/artifacts |
| diff | `git diff --check` app | exit0 | exec 완료결과 |

## 담당 기능 결과

모두 desktop/mobile PASS: 회색 원문 지속/검정 입력, 지우기, 오타 밑줄, 일반 및 문장 중간 한글 composition 중 오류 유예, 실제 browser clipboard 붙여넣기, 명시적 완료, 완료 재진입 readonly, 자동 저장503/retry, in-flight 저장 동안 최신입력 보존 및 직렬화, 409후자동저장중지 및 현재입력유지, 명시적 reload취소/수락, 성경절에서 새필사시작/목록/이어쓰기, 삭제취소/확인/409후새확인, 목록503/상세404 retry, 로그아웃 지연A응답과 B계정 격리, 미로그인 private deep link gate, 원격삭제409+reload404 후미저장입력폐기와목록복귀, 개인필사 browser localStorage/sessionStorage 미저장, 가로overflow없음.

합성 browser composition 이벤트를 사용했으며 실제 OS 한글 입력기 검증으로 표현하지 않습니다. clipboard는 Chromium permission과 실제 Meta/Control+V를 사용했습니다.

## 독립 근거 확인 — 재실행하지 않음

- 서버 담당 구현/테스트 내용을 별도로 읽고 정상/검증/본인권한/완료/rollback/불변 snapshot/동일·타인 UUID 경합/탈퇴·user행잠금 검사 조건을 확인했습니다.
- `backend-postgres-v423-final.xml`: 9tests9pass0fail0error0skip. 실제 PostgreSQL/Flyway45migrations V42.3 + validate + lock inspection. 담당 exit0/47s는 `backend-final-summary.json`, `.log`에 기록.
- `backend-full-v423-final-xml/`: 53suites260tests257pass0fail0error3skip(전용 PostgreSQL 검사). 담당 exit0/2m2s. PostgreSQL에서 해당 필사 잠금 전용 test는 실제 pass임을 따로 확인.
- 앱 typecheck/단위검사113/웹build exit0는 Lead 원본 실행 근거 공유를 확인했고 QA가 같은 명령을 중복 실행하지 않았습니다.

## 버그/수정 확인

- QA-001 MEDIUM: 409→원격삭제→reload404→discard복귀 시 목록에 삭제된 항목의 캐시가 남음. 최초 desktop/mobile 재현. Lead가 목록 focus refetch/폐기시invalidate 및 error/loading header뒤로를 추가. 최종 browser16/전체82에서 재검증 PASS.
- QA-T01 테스트 selector: 완료재진입 때 hidden이전화면의 같은 완료문구까지 strict locator에 잡힘. visible filter로 테스트만 수정. 실제 제품 readonly/원문재진입은 정상.
- 독립 Reviewer가 제기한 완료재진입 stale snapshot 및 409삭제후탈출 위험의 최종 행동을 browser로 추가 확인했습니다.

## 화면 확인

QA가 최종 desktop/mobile 원본 PNG를 직접 열어 black/gray분리, 같은 줄 정렬, 잘림/가로overflow를 확인했습니다.
- `qa-pwa-full-final-artifacts/scripture-copy-persistent--3a468--and-wraps-without-overflow-desktop/scripture-copy-follow-writing.png`
- `qa-pwa-full-final-artifacts/scripture-copy-persistent--3a468--and-wraps-without-overflow-mobile/scripture-copy-follow-writing.png`
- `qa-pwa-full-final-artifacts/scripture-copy-save-failur-06c7c-ste-can-complete-explicitly-desktop/scripture-copy-completed.png`
- `qa-pwa-full-final-artifacts/scripture-copy-save-failur-06c7c-ste-can-complete-explicitly-mobile/scripture-copy-completed.png`

## 미실행 / 비해당

- 실제 iOS/Android 기기·OS한글IME·네이티브 키보드/레이아웃: 장비/실행환경 미제공, 미실행. web mobile 에뮬레이션과 구분.
- 실제 운영 계정/운영 데이터/운영 배포·스토어 출시: 승인 범위 밖, 비해당.
- CI/PR/병합: Lead 진행, QA local PASS는 CI/배포 승인을 대신하지 않음.

최종 QA에서 미해결 BLOCKER/HIGH 및 재현된 기능결함 없음. 위 native 미실행은 알려진 검증 범위 제한으로 기록합니다. 최종 로컬 QA 판정 MERGE READY; 필수 CI와 운영 배포 승인 기준은 별도입니다.
