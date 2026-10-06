# 독립 QA / Review: 위험 기반 로컬 검증 (#144 / #125)

- 담당자: `/root/question_scope_qa`. 정책·제품 파일을 작성하지 않은 독립 QA/Reviewer이며 이 보고서만 작성했다.
- 계약: 서버 `test-policy-144/docs/tasks/144-risk-based-local-tests.md` v1 및 허용 경로 확대 CR-001. 앱 `test-policy-125/docs/tasks/125-risk-based-local-tests.md`.
- 최종 판정: **MERGE READY — 문서 정책과 로컬 문서 검증 완료. 최종 커밋의 필수 CI·병합/운영 승인 완료를 의미하지 않는다.** 미해결 BLOCKER/HIGH/MEDIUM 없음.
- 대상: 서버 base dbbe484 / 앱 base b6fb746의 정책 delta 및 현재 main-2 사용자의 기존 미커밋 초안에 대한 검증 안내 delta. 최종 7파일 manifest SHA256 `243b3de4145740bb70172d749d6e946b42a56f5d9ee0fbe7a8dafeeaf7636d08`와 현재 파일이 모두 일치한다.

## 대상 파일과 검토 근거

원본 target manifest: `/private/tmp/risk-based-testing-checks/policy-source-hashes.json`.

| 파일 | 최종 SHA256 |
| --- | --- |
| `/Users/byeongdoohan/orca/workspaces/mypasture-server/main-2/AGENTS.md` | `d7b45e54c450ffa98bebcff20ced6d580a91ae63062043cba9bbfeb97e7ed0fe` |
| `/Users/byeongdoohan/orca/workspaces/mypasture-server/main-2/.github/pull_request_template.md` | `76fe9ab1a7aef7b1ac70b6c8609c768fe2ecdf7091896ac7aef051edf36b2dcc` |
| `/Users/byeongdoohan/orca/workspaces/mypasture-server/main-2/docs/development/multi-agent-workflow.md` | `0123a00b12d1e6ebb1e76f128c925c4e7d696898c479bcc163589056f84a87cf` |
| `/Users/byeongdoohan/orca/workspaces/mypasture-server/test-policy-144/AGENTS.md` | `661990efa703265a1dd0e626812413ba0aaa995a1548358b202011ddad4179d0` |
| `/Users/byeongdoohan/orca/workspaces/mypasture-server/test-policy-144/.github/pull_request_template.md` | `dea7f0e74eb3e2fe1128a3c0a658ad405a5e409be53d1478e6c25ec7c6959f40` |
| `/Users/byeongdoohan/orca/workspaces/mypasture-mobile/test-policy-125/AGENTS.md` | `5f15b3e95e8926ccaee0abef9ab024260c37c2e714ae1e44a975becd9d51222c` |
| `/Users/byeongdoohan/orca/workspaces/mypasture-mobile/test-policy-125/.github/pull_request_template.md` | `f47db952265426f61e656ce7c18f3fc6f3af061453391587b3385536b2861609` |

서버·앱 실제 `git diff`와 신규 작업 기록을 읽었다. current main-2의 AGENTS/PR 양식/workflow는 다음 원본과 직접 비교했다:

- `server-current-draft-before.md`
- `server-current-pr-template-before.md`
- `server-current-workflow-before.md`

같은 디렉터리의 `server-current-*-policy.diff`는 기록 근거다. QA도 snapshot에서 현재 내용을 직접 diff하여 기존 사용자 편집의 보존을 확인했다. current AGENTS의 변경된 최상위 절은 테스트 토큰 비용 관리·변경 범위별 검증·독립 QA와 리뷰의 세 절뿐이며 나머지 13개 절/서문은 byte 단위로 동일하다. 비용 관리와 독립 리뷰에서는 검증 안내 문장만 변경했다. current PR 양식은 규모/검증 위험도 분리 및 검증 상태/로컬 전체 row만 변경했고, workflow는 검증 위험도/생략 기록·Tests/QA template·필수 검사 누락 판단 안내만 변경했다. 승인·역할·계약·도메인 소유권·보안·데이터·배포 기준 등 다른 초안 내용은 보존됐다.

두 isolated worktree의 변경 파일은 AGENTS와 PR 양식, 신규 작업 기록뿐이다. 두 repository의 모든 `.github/workflows/*`를 HEAD 원본과 byte 비교해 같음을 확인했다. 실행 코드·package/build 설정·의존성 변경 없음.

## 정책 시나리오 QA

아래는 문서 규칙을 실제 가상 변경/실패 조건에 적용한 정적 QA다. 애플리케이션 동작 테스트를 실행한 결과가 아니다.

| 조건 | 정책상 필요한 처리 | 검토 결과 |
| --- | --- | --- |
| 호출자/기능 경계가 명확한 새 서버 기능/API, auth/소유권/DB 위험/공유 설정 변경 없음 | 담당·직접 인접 회귀, API/interface/http 및 diff 검증. 로컬 전체는 정책상 생략, 최종 전체 CI 필수 | 일치. 새 API라는 이유만으로 자동 고위험 또는 자동 저위험으로 정하지 않음 |
| 기존 API를 사용하는 독립 화면/표시 로직, 인증·공유 상태 변경 없음 | typecheck·실제 담당/인접 unit/PWA, 웹 build·관련 UI 크기/플랫폼 확인. 전체 unit/PWA만 기본 생략 | 일치. 타입/build/UI 검사가 로컬 전체 생략에 포함되지 않음 |
| 단일 기능/한 파일의 객체 소유권 필터·인증·권한·계정 격리 변경 | 고위험. 담당/경계/권한 검사 후 영향 repository 전체 | 일치. 공통 코드라는 조건 없이 고위험이며 Small/파일 수와 별개 |
| 결제·구독 권한·보상·quota/원장 정책 변경 | 고위험, 전체 및 분야별 실패/중복 검사 | 일치 |
| schema/migration·삭제/보유·transaction·영속 멱등성·동시성 변경 | 고위험 서버 전체 + PostgreSQL/Flyway, 경합/rollback/data 영향 | 일치. H2 통과만으로 PG를 대체하지 않음 |
| 공유 HTTP client/직렬화/공유 상태·캐시/보안·실행 설정 또는 중요한 의존성/build 변경 | 고위험, 영향 repo 전체 및 필요한 실제 build/config 검사 | 일치. client/계정 격리 검사를 feature 범위 밖이라고 제거하지 않음 |
| 영향 불명확 또는 관련 검사 실패/미해결 회귀 우려 | 고위험 기준으로 넓혀 검증 | 일치. 처음 저위험 판정이 실패를 덮는 근거가 아님 |
| 담당 선택 명령에서 발견한 테스트가 0개 또는 오래된 보고서만 남음 | 통과 아님. 실제 담당·인접 검사 근거 없으면 완료/병합 불가 | 일치. 선택/발견/통과/실패 수 기록, QA가 선택 충분성을 확인 |
| 저위험 로컬 전체를 의도적으로 실행하지 않음 | `정책상 생략: 저위험 근거와 대체 검사` 기록. `통과`/`CI 완료`로 표시하지 않음 | 일치. 필수 local 누락 판단에서 이 합법적 생략만 제외 |
| 담당·인접·분야별 검사 또는 고위험 전체가 없거나 실패 | 필수 검사 누락/실패 기준 유지, CHANGES REQUIRED | 일치. 외부 서비스 부재를 기록한 것만으로 필수 완료로 처리하지 않음 |
| 앱 네이티브 전용 변경 | 실제 해당 플랫폼 검사 유지. 웹 무영향 근거가 있으면 무관한 웹 검사 비해당 | 일치. native auth/shared state는 고위험, 영향 불명확이면 전체 확장 |
| 변경 대상이 앱뿐인 작업 | 영향받는 repository/플랫폼의 검사 선택. 다른 repo 로컬 전체를 자동 실행하지 않음 | 일치 |
| 동일 최종 소스/설정/의존성/환경의 성공 근거 있음 | 근거 없이 반복하지 않고 원본 재사용 | 일치. source/config 변화·실패·새 위험이면 영향 검사와 CI 갱신 |
| 로컬 담당 검사는 성공했지만 CI job 실패 | 저위험도 최종 CI 통과 전 병합/배포 불가 | 일치. PG/build/hosting 등 실패 job·보호 규칙 우회 금지 |
| 'CI에서 한 번'이라고 기록했지만 PR와 main push 모두 CI trigger | 기존 자동 trigger 유지, 재실행 금지·중복 이벤트 제거로 해석하지 않음 | 일치. 실제 pipeline 실행 횟수를 한 번이라고 약속하지 않음 |
| 실행에 영향 없는 문서만 변경 | diff·링크/명령/실제 설정 검사, runtime 테스트 정책상 생략. 기존 전체 CI와 자동 배포 승인 조건 별도 유지 | 일치. 이번 변경이 해당하며 runtime 결과를 통과로 주장하지 않음 |

세 AGENTS의 공통 위험도·CI 블록은 byte 단위로 동일하다. 앱의 고위험 표는 feature-level 고위험을 포함하도록 '공통' 제한 표현이 제거됐다.

## 명령 및 CI 일치 검증

- 서버 선택 예 `./gradlew test --tests 'com.example.mypasture.ai.*'`: 현재 Gradle/JUnit test 설정 및 실제 AI test package와 일치. 명령을 실행했다고 보고하지 않는다.
- 앱 선택 예 `node --experimental-strip-types --test src/features/ai/idempotency.test.ts`: 실제 존재하는 test 파일 및 기존 package script 방식과 일치.
- 앱 PWA 선택 예 `npm run test:pwa -- bible-search.spec.ts`: 실제 파일과 package의 Playwright command 일치.
- `npm test`는 기존 script에 모든 파일을 열거하므로 파일 인자 추가만으로 좁아지지 않는다는 주의가 실제 설정과 일치한다.
- 서버 CI의 전체 Gradle, PostgreSQL/Flyway 대상 job, Docker build, Terraform 검증과 앱 CI typecheck·전체 unit/PWA·build·hosting·critical audit는 그대로다. 기존 PR/main push/workflow_dispatch trigger와 자동 배포 workflow를 변경하지 않는다.
- QA가 세 worktree의 `git diff --check` 직접 실행: 모두 exit 0. 문서/명령/설정 검사 외 runtime 테스트는 **정책상 생략**이며 pass로 계산하지 않는다.

## Review 지적 및 해결

| 심각도 | 지적 | 해결 상태 |
| --- | --- | --- |
| LOW | 서버 main GitHub 규칙의 무조건 local full 요구가 저위험 정책과 충돌할 수 있음 | FIXED. 위험도별 필수 local + final full CI로 정렬 |
| LOW | 새 고위험 범위가 공통 인증만 포함하는 것으로 읽힐 수 있음 | FIXED. 인증·권한·객체 소유권·계정 격리를 직접 명시 |
| LOW | 앱 고위험 표의 '공통 변경'이 개별 feature 고위험에 대한 전체 검사를 놓칠 수 있음 | FIXED. 공통 제한 표현 제거, universal 위험 조건 유지 |
| LOW | current workflow Tests/QA fenced template에는 정책상 생략 상태와 선택 검사 충분성 기록이 빠질 수 있음 | FIXED. 해당 template에 상태·선택/발견/pass/fail 수·0case·CI 경로 추가 |
| LOW | '최종 CI 한 번'이 기존 PR/main trigger 축소를 의미하는 것처럼 읽힐 수 있음 | FIXED. 기존 자동 trigger 유지 및 재검증 금지 아님을 명시 |

위 LOW 지적 수정 후 현재 diff/manifest를 재확인했다. 미해결 BLOCKER/HIGH/MEDIUM/LOW 없음. 불필요한 정책·제품 변경이나 기존 user draft 덮어쓰기는 발견하지 않았다.

## 잔여 조건

- 최종 commit의 기존 필수 CI 확인은 Lead가 수행한다. 이 보고서는 CI 성공·병합·운영 배포 완료를 뜻하지 않는다.
- 서버 진행 중 PR #145/#148가 이 정책을 되돌리지 않도록 새 정책 Issue/PR/계약을 연결하는 후속 통합 기록은 Lead 책임이다. 해당 PR을 이 작업에 포함하거나 자동 병합하지 않는다.
- Reviewer는 위험 판정이 형식적으로만 기록되지 않도록 future 변경의 실제 diff와 담당·인접 검사 선택을 계속 확인해야 한다. 새 정책은 CI 및 필수 분야별 검사를 줄이는 승인이 아니다.
