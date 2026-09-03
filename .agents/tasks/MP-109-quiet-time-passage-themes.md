# 작업 계약: MP-109 본문 주제 기반 QT 질문 세트

## 목표

한 성경 본문에 복수의 가중 주제를 연결하고 리더가 선택한 초점으로 질문을 확정해 모든 참여자가 동일한 질문으로 나눕니다.

## 담당

- Owner: community-backend
- Reviewers: scripture-intelligence, qa-security, coordinator
- 최종 통합 담당: coordinator

## 범위

- 포함: 주제 사전, 본문-주제 가중치, 질문 미리보기, 세션 질문 스냅샷, OpenAPI, HTTP, 테스트
- 제외: 전 성경 AI 일괄 분류 배치, 관리자 신학 검수 화면

## 완료 조건

- [x] 본문별 기본·보조 주제와 근거 조회
- [x] 초점·진행 방식별 질문 미리보기
- [x] 세션 생성 시 질문과 주제 불변 스냅샷 저장
- [x] 답변이 세션 질문 문구를 참조
- [x] 전체 테스트와 PostgreSQL migration 검증
- [x] 인수인계 작성

## 실행할 검증

```text
./gradlew test
git diff --check
```

## 의존성과 위험

- 기존 추천 본문은 하위 호환으로 유지합니다.
- AI 자동 분류 결과는 바로 게시하지 않고 같은 주제 사전과 검수 절차를 사용해야 합니다.
