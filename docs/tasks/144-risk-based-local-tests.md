# 작업 계약: 위험 기반 로컬 검증 (#144)

## Requirement
사용자가 제안한 1단계 정책을 승인했다: 저위험 변경은 로컬 담당·인접 회귀만 실행하고 최종 전체 검사는 기존 CI에서 한 번 확인한다. 고위험·영향 불명확 변경의 로컬 전체, 분야별 담당 검사, 독립 QA/Review와 배포 전 CI를 유지한다. CI 선별·일정 변경은 이번 요청에 포함하지 않는다.

## Existing System
서버 main dbbe484의 AGENTS는 코드 변경마다 로컬 ./gradlew test를 요구한다. 앱 main b6fb746의 AGENTS는 모든 공통 로직/화면/API에 npm test, 웹 공통 변경에 전체 PWA를 요구한다. 서버 PR #145(규칙 전체 정리)와 #148(로그 비용 관리)이 열려 있으며 현재 main-2에는 기존 사용자 규칙 초안·PR template·workflow 양식이 미커밋 상태다. 서버 관련 Issue #144 재사용, 앱 Issue #125. 두 CI는 전체 검사를 실행하고 서버 main CI 성공은 public API 운영 자동 배포로 이어진다.

## Impact
규모: Medium — 두 저장소 검증 정책 및 현재 서버의 진행 중 규칙 초안/검증 양식의 연동 변경이다. 실행 코드·DB·API·의존성·CI workflow 변경 없음. 분류가 잘못되면 검증 누락 위험이 있어 별도 QA/Review로 예외·기본값·필수 CI 경계를 확인한다.

## Work Breakdown
| 작업 | 역할 / 실제 Agent | Owner | 허용 경로 |
| --- | --- | --- | --- |
| 정책 계약·문서 구현·통합 | Lead /root | Backend Foundation | 두 새 worktree AGENTS.md, .github/pull_request_template.md, docs/tasks/<id>-risk-based-local-tests.md; 현재 main-2 AGENTS.md 및 .github/pull_request_template.md·docs/development/multi-agent-workflow.md의 검증 안내만 |
| 독립 QA/Review | /root/question_scope_qa (후속 작업) | Backend Foundation | 위 변경 읽기; /private/tmp/risk-based-testing-checks/qa-review.md만 작성; 제품/정책 파일 직접 수정 없음 |

현재 사용자 파일은 원본 snapshot을 보존하고 검증 관련 절만 변경한다. 신규 PR은 최신 main의 작은 정책 delta이며 기존 PR #145/#148를 포함하거나 병합하지 않는다. 진행 중 규칙 PR에 연동 정책을 링크해 이후 통합 시 새 기준을 유지하도록 기록한다.

## Contracts
v1 확정자 /root.

API / DB / Event / 실행 공통 Interface: 없음. 새 환경변수·모델·빌드/배포 설정 없음.

정책 Interface:
- 영향받는 저장소의 검사만 판단한다. 작업 규모와 검증 위험은 별도로 판단한다.
- 저위험은 기능 경계 명확, 인증/권한/객체 소유권 검사/계정 격리·결제/권한/보상/quota 정책·스키마/migration/삭제/동시성·공유 client/상태/보안/설정·중요 의존성/빌드 변경 없음, 담당·인접 검사로 영향 범위를 설명할 수 있다는 모든 조건이 필요하다. 새 기능/API라는 이유만으로 전체 필수도 자동 저위험도 아니다.
- 저위험 로컬은 담당/인접 회귀, diff, 필요한 타입·build·UI/플랫폼·외부 의존성 검증을 유지하며 로컬 전체를 기본 생략한다. 고위험 조건이 하나라도 있거나 범위 불명확·미해결 회귀 우려면 로컬 전체.
- 선택한 명령·실제 발견/통과/실패·대상·환경·생략 범위와 이유를 기록한다. 선택 결과 0개는 통과 아님. 로컬 전체는 '정책상 생략'으로 기록하고 '통과'나 'CI 완료'로 표시하지 않는다. QA는 실제 diff와 검사 선택의 타당성을 확인한다.
- CI의 기존 전체 검사와 PG/build/hosting 등 필수 job은 유지. 최종 source/설정이 같을 때 성공 결과를 재사용하며 변경/실패/새 위험이 있으면 영향 검사 갱신. '최종 1회'는 재검증 금지·필수 CI 생략이 아니다. 배포는 최종 CI와 기존 운영 승인 기준이 필요하다.
- 현재 규칙의 '필수 로컬 검증 누락은 CHANGES REQUIRED'는 정책상 허용된 전체 생략에는 적용하지 않고, 담당/인접/분야별 필수 또는 고위험 전체 누락에는 적용한다.

## Dependencies / Risks
정책 확정 → 문서 delta → 현재 draft 검증 절 동기화 → diff/명령·양식 일치 검사 → 독립 QA/Review → 최종 commit/PR CI. 기존 PR #145/#148의 오래된 전체 요구를 나중에 되살리지 않도록 연동 기록 필요. 도메인 파일 수만 보고 저위험으로 판정하거나 CI 결과로 로컬 필수 담당 검사를 없애지 않는다. 정책 변경은 실패 검사를 통과시키는 수단이 아니다.

## Definition of Done
CR-001 수락 /root: 두 최신 main PR 양식에도 위험도·로컬 전체 정책상 생략·최종 전체 CI 기록을 추가한다. 기존 양식의 무조건 전체 통과 체크가 새 규칙과 충돌하지 않도록 하는 범위이며 다른 PR/계약/운영 규칙은 보존한다. 독립 검토자에게 경로 확대 전달한다.
- [x] 서버·앱 위험 조건 및 검증 표 일치
- [x] 현재 사용자 초안의 관련 절만 갱신, 다른 내용 보존
- [x] 독립 QA/Review 및 문서 검사
- [ ] 최종 PR CI 전체 확인
- [ ] 적용/병합/운영 배포 상태 구분 보고

## 검증 계획
Root: 두 repo diff --check, package/build 명령과 실제 workflow 일치, CI workflow diff 없음, 기존 draft snapshot 대비 변경 절 범위 확인. 문서 전용이므로 로컬 서버/앱 실행 테스트는 정책상 생략. QA는 저위험 단순 API/화면, 고위험 auth/DB/공유 client, 불명확·실패·0test·CI 실패 사례의 문서 정책 적용을 검토한다. 전체 CI는 기존 그대로 실행하며 서버 main 배포 승인은 없음.

## Lead Integration / Implementation Handoff
Root가 두 repo AGENTS와 PR 양식, 현재 main-2의 검증 관련 초안/양식을 수정했다. 코드·API·DB·환경변수·workflow 변경 없음. 독립 QA/Reviewer는 제품·정책 파일을 수정하지 않고 실제7파일 hash·snapshot diff·명령/CI 일치와 정적 정책16사례를 검토했다. [QA/Review](144-risk-based-local-tests-qa-review.md) MERGE READY, 미해결 지적 없음. Root가 실제 diff와 최종 hash를 대조했다.

근거: `/private/tmp/risk-based-testing-checks/policy-source-hashes.json` SHA256243b3de4145740bb70172d749d6e946b42a56f5d9ee0fbe7a8dafeeaf7636d08, `document-checks.json`, 기존 draft3개의 before/diff snapshot. 두 새 worktree와 현재 worktree diff --check exit0. Gradle/API test package, npm 고정 file list, 단위/PWA 선택 예제와 실제 CI job 일치. 기존 사용자 초안의 다른13개 절 보존 확인.

이번 변경은 실행 영향 없는 문서이므로 로컬 서버 전체·앱 단위·PWA 등 runtime 검사는 정책상 생략했다. 생략을 통과로 집계하지 않는다. 최종 PR CI는 기존 전체대로 실행하고 결과는 PR/Issue에서 갱신한다. 자동 PR/main 이벤트 실행 횟수는 이번 변경으로 줄이지 않는다. 기존 PR145/148와 통합할 때 새 위험도 기준을 유지하도록 연동 기록한다. 운영 배포 승인 없음.
