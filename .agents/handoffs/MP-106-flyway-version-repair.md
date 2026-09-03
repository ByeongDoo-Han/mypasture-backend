# 에이전트 인수인계

## 작업 정보

- 작업 ID: MP-106
- 브랜치: `feat/foundation-106-flyway-version-repair`

## 완료한 내용

- 먼저 병합된 AI 대화 V30은 유지하고 staging에 적용되지 못한 장별 해설 migration을 V33으로 이동
- V1의 평문 대화 테이블은 삭제하지 않고 `*_legacy`로 보존한 뒤 V30의 암호화 대화 테이블을 생성하도록 복구
- Flyway migration 버전 중복을 CI에서 감지하는 회귀 테스트 추가

## 검증 결과

- `./gradlew test`: 통과
- 빈 `pgvector/pgvector:pg16` PostgreSQL에 V1-V33 전체 적용: 통과
- `JPA_DDL_AUTO=validate` 스키마 검증: 통과
- `git diff --check`: 통과

## 남은 위험

- staging 자동 배포 실패가 발생했으므로 병합 후 migration job 상태를 별도로 확인해야 함
