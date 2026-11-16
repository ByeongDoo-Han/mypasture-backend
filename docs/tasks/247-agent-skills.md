# 작업 기록: #247 서버 검증·API 계약 스킬

## 계획과 근거

- 종류: 개발 절차 개선
- Issue: [#247](https://github.com/ByeongDoo-Han/mypasture-backend/issues/247), 구현 [PR #248](https://github.com/ByeongDoo-Han/mypasture-backend/pull/248)
- 기존 상태: 위험도 정책과 CI 선택기는 있으나 서버 서브에이전트가 변경 검증과 API 소비자 인수를 실행할 프로젝트 스킬이 없었다.
- 목표: 실제 diff 기반 검증과 서버 계약·모바일/관리자 인수를 반복 가능한 절차로 제공한다.
- Owner·허용 경로: 서버 문서 담당 / `AGENTS.md`, `.agents/skills/**`, `.github/scripts/select_tests.py`, `.github/scripts/test_select_tests.py`, `docs/tasks/**`

## 실제 변경

- `mypasture-change-verification`: 위험도별 검사 선택, 실제 수와 미실행 사유, 최종 커밋 CI 인수.
- `mypasture-api-contract`: `*Api`·`.http`·오류·권한·구버전 호환 확인과 소비자 인수.
- `AGENTS.md`에 발동 조건을 연결하고 CI 선택기에서 `.agents/skills/`를 문서 경로로 분류했다.
- API/DB/환경변수/실행 서비스 영향 없음. 현재 기능·정책 문서 갱신 비해당.

## 검증

- `quick_validate.py`: 스킬 2/2 유효.
- 상대 Markdown 링크: 누락 0개.
- `python3 .github/scripts/test_select_tests.py`: 8/8 통과. 스킬 문서의 앱 검사 0개 선택과 스킬 내 실행 스크립트·미분류 경로의 전체 검사 유지 확인.
- CI 선택기를 실제 변경 경로에 실행: unit 0, PostgreSQL 0, runtime/container false.
- `git diff --check`: 통과.
- 앱 테스트: 실행 경로가 바뀌지 않아 0개. CI 선택 코드의 회귀는 위 선택기 검사로 확인.

## 단계

| 단계 | 작성 시점 상태 |
| --- | --- |
| 로컬 구현·검증 | 위 검사 완료, `4b1b078` |
| PR 최종 커밋 CI | [Server CI run 43815506264](https://github.com/ByeongDoo-Han/mypasture-backend/actions/runs/43815506264) 통과 |
| `dev` 병합 | PR #248 merge `31a6ecf`, 원격 기능 브랜치 삭제 |
| `main` 병합·운영 배포 | 미실행 |

이후 상태는 이슈와 PR의 최신 커밋·CI에서 확인한다.
