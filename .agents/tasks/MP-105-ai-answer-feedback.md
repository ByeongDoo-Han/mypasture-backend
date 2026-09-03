# 작업 계약: MP-105 AI 목자 답변 피드백

## 목표

답변별 평가를 안전하게 축적하고 아쉬움 사유를 반영한 무료 개선 답변을 한 번 제공합니다.

## 담당

- Owner: backend-ai
- Reviewers: qa-security, coordinator
- 최종 통합 담당: coordinator

## 범위

- 포함: 피드백 저장, 암호화 의견, 무료 재생성, RAG 재검색, 운영 집계, OpenAPI, HTTP, 정책 문서, 테스트
- 제외: 사용자 자유 의견의 자동 학습, 관리자 원문 의견 열람, 모델 fine-tuning

## 완료 조건

- [x] 답변 소유권과 입력 정책 검증
- [x] 사용자·답변별 멱등 갱신과 동시 재생성 제어
- [x] 무료 재생성 사용량 미차감과 비용 추적
- [x] 전체 테스트와 PostgreSQL migration 검증
- [x] 인수인계 작성
