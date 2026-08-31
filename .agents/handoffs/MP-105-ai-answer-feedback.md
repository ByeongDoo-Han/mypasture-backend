# 에이전트 인수인계

## 작업 정보

- 작업 ID: MP-105
- 브랜치: `feat/ai-105-answer-feedback`

## 완료한 내용

- 답변 turn을 사용량 로그 ID와 연결하고 RAG 근거 verse ID를 추적
- 도움됨/아쉬움과 사유 1~3개, AES-GCM 암호화 의견을 멱등 저장
- 동일 질문 HMAC 검증, 동시 잠금, 성공 1회 제한이 있는 무료 개선 생성
- 자유 의견을 AI에 전달하지 않고 서버 사유 코드를 안전한 개선 지침으로 변환
- 비식별 관리자 집계와 잘못된 성경 구절 우선 검수 API 추가

## 검증 결과

- `./gradlew test`: 전체 성공
- pgvector PostgreSQL 신규 DB: Flyway V1~V34 적용 및 JPA schema validate 성공
- 답변 소유권, 피드백 멱등 갱신, 타 사용자 차단, 무료 재생성 미차감과 parent turn 연결 검증

## 남은 위험

- 실제 OpenAI 응답 품질은 운영 피드백 분포와 사유별 전환율을 관찰해 개선해야 함
- 자유 의견은 암호화 저장만 하며 현재 운영 API에서 원문을 조회하지 않음
