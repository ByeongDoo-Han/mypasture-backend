# 작업 계약: MP-103 장별 성경 해설 파이프라인

## 목표

장별 성경 해설을 승인된 근거 자료와 자동 평가를 거쳐 생성하고, 관리자가 수정·검수한 콘텐츠만 게시할 수 있게 합니다.

## 담당

- Owner: scripture-intelligence
- Reviewers: qa-security, coordinator
- 최종 통합 담당: coordinator

## 범위

- 포함: 근거 메모, 자동 평가, 초안 수정, 검수 기록, 책 단위 생성, API와 테스트
- 제외: 외부 저작권 주석 원문 수집, 운영 데이터 일괄 생성, 모바일 UI 변경

## 허용 경로

- `src/main/kotlin/com/example/mypasture/bible/**`
- `src/main/kotlin/com/example/mypasture/ai/application/AiRuntimeConfigService.kt`
- `src/main/kotlin/com/example/mypasture/ai/domain/AiRuntimeModels.kt`
- `src/test/kotlin/com/example/mypasture/bible/**`
- `src/main/resources/db/migration/**`
- `http/**`
- `.agents/**`

## 공유 계약

- API 요청/응답: 관리자 장별 해설 근거·수정·일괄 생성 API 추가
- 이벤트: 없음
- DB 변경: 해설 근거, 자동 평가, 검수 기록 테이블/컬럼 추가
- 환경변수: 기존 OpenAI 설정 재사용
- 모바일/관리자 영향: 관리자 콘솔 후속 연동 필요, 공개 조회 계약은 하위 호환 유지

## 완료 조건

- [x] 정상 흐름 구현
- [x] 실패 및 권한 흐름 구현
- [x] 단위/통합 테스트 통과
- [x] OpenAPI와 `.http` 갱신
- [x] 환경변수 변경 없음 확인
- [x] 보안 및 개인정보 점검
- [x] 인수인계 작성

## 실행할 검증

```text
./gradlew test
git diff --check
```

## 의존성과 위험

- 선행 작업: 장별 해설 생성 큐와 공개 조회 API
- 충돌 가능 파일: 신규 Flyway migration, 관리자 API contract
- 운영 위험: 자동 평가 실패 시 해설 생성 작업 실패 처리
- 사람의 결정이 필요한 항목: 실제 게시 전 신학 검수자와 승인 근거 자료
