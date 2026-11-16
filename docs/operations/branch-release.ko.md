# 서버 브랜치 통합과 릴리스

1. 기존 Issue를 확인하고 최신 `dev`에서 `feat/<domain>-<issue>-<slug>` 브랜치를 만듭니다. 다른 작업 트리의 미완료 변경은 보존합니다.
2. 세부 작업 PR의 base는 `dev`입니다. 변경 영향에 따른 로컬 검사와 PR CI가 통과하면 `dev`에 병합하고 작업 브랜치를 정리합니다.
3. 릴리스 후보는 `dev`에서 `main`으로 PR을 만듭니다. 포함된 Issue/PR, API·DB·환경 변경, 필요한 검사 결과와 배포 순서를 적습니다. `main` PR의 출처는 CI가 `dev`로 제한합니다.
4. 최종 CI와 승인 조건을 확인한 뒤 `main`에 병합합니다. `main` push의 Server CI가 성공하고 변경 선택 결과의 `runtime`이 참이면 [GCP 배포 워크플로](../../.github/workflows/deploy-gcp.yml)가 staging 배포를 시작합니다. production은 `main`에서 수동 실행하고 별도 승인을 받습니다.
5. 배포 커밋, migration, API/worker 준비 상태와 핵심 흐름을 확인합니다. CI·병합·staging·production을 별개 상태로 기록합니다.

기존 `dev`가 없는 상태에서 2026-11-16의 `main` 커밋으로 새로 시작했습니다. 이 문서는 배포 성공 자체를 주장하지 않습니다.
