# 작업 계약: MP-106 Flyway 중복 버전 복구

## 목표

중복된 V30 migration 때문에 staging migration job이 시작되지 못하는 문제를 복구하고 CI 회귀 검사를 추가합니다.

## 담당

- Owner: backend-foundation
- Reviewers: qa-security, coordinator
- 최종 통합 담당: coordinator

## 범위

- 포함: 미적용 장별 해설 migration 번호 이동, V1/V30 대화 테이블 충돌 복구, migration 버전 중복 테스트
- 제외: 운영 DB 수동 수정, API와 도메인 로직 변경

## 완료 조건

- [x] 중복 migration 버전 제거
- [x] 전체 테스트 통과
- [x] PostgreSQL 전체 migration 통과
- [x] 인수인계 작성

## 실행할 검증

```text
./gradlew test
git diff --check
```
