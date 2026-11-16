---
name: mypasture-change-verification
description: Select and report proportionate My Pasture backend checks when server code, tests, migrations, build settings, or documentation changes.
---

# 서버 변경 검증

작업의 실제 diff와 대상 커밋을 기준으로 [저장소 검증 규칙](../../../AGENTS.md)과 [CI 선택기](../../../.github/scripts/select_tests.py)를 읽는다. 이 스킬은 기존 정책을 실행하는 절차이며 검사 기준을 새로 정의하지 않는다.

1. 변경 경로와 호출자를 확인하고 저위험·고위험·영향 불명확 중 하나로 분류한다. 선택 근거를 Issue, PR 또는 [작업 기록](../../../docs/tasks/TEMPLATE.md)에 남긴다.
2. 담당 도메인과 직접 연결된 회귀를 선택한다. 인증·권한·DB·동시성·공유 설정 등 고위험이면 AGENTS.md의 위험 경계 검사를 넓힌다. PostgreSQL/Flyway 영향은 실제 PostgreSQL 환경으로 확인한다. 공통 변경으로 영향이 전역일 때만 전체 테스트를 선택한다.
3. 선택한 검사를 실행하고 명령, 대상 커밋·환경, 발견·통과·실패 수, 제외 이유를 기록한다. `git diff --check`는 모든 변경에서 실행한다. 실행 경로에 영향 없는 문서 변경은 애플리케이션 테스트 0개와 이유를 기록한다.
4. 실행 코드 변경에 선택 검사 0개가 나오거나 필요한 외부 환경이 없어 필수 검사를 못 했다면 통과로 처리하지 않는다. 실패와 미실행의 영향 및 다음 검증 경로를 적는다.
5. PR 최종 커밋의 관련 CI를 확인하고 로컬 결과·CI·병합·배포 상태를 구분해 인계한다. 소스·설정·환경이 바뀌었거나 새 위험이 발견되면 영향받는 검사를 다시 선택한다.

스크립트가 계산하는 선택 결과와 저장소 정책이 충돌하면 임의로 우회하지 말고 실제 diff와 위험 근거를 함께 보고한다.
