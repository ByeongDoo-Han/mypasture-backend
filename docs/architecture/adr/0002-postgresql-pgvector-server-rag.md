# ADR-0002: PostgreSQL·pgvector 기반 서버 RAG 사용

- 상태: Accepted
- 결정일: 2026-08-29

## 배경

AI 목자는 실제 성경 구절에 근거해야 하며 모바일 로컬 데이터만으로는 중앙 정책, 최신 embedding, 사용량과 비용을 일관되게 통제하기 어렵습니다. 성경 전체를 매 요청마다 모델에 전달하는 방식은 비용과 hallucination 위험이 큽니다.

## 결정

성경 원문과 embedding을 PostgreSQL에 절 단위로 저장하고 pgvector로 Top-K 후보를 검색합니다. 서버가 후보를 원문과 재검증한 뒤 필요한 구절만 OpenAI에 전달하며, 최종 citation도 허용 집합과 대조합니다.

## 대안

- 모바일 SQLite 성경 데이터로 클라이언트 RAG 수행
- 성경 전체를 prompt에 포함
- Pinecone 같은 별도 vector database 사용
- lexical 검색만 사용

## 결과

긍정적 결과:

- 원문 transaction 데이터와 vector를 한 운영 체계에서 관리합니다.
- RAG 정책, quota, cache, token 비용 기록을 서버에서 강제합니다.
- 절 단위 북마크·인용과 동일한 원문 ID를 사용할 수 있습니다.

부정적 결과:

- PostgreSQL이 transaction과 vector 부하를 함께 감당합니다.
- embedding model·dimension 변경 시 재적재와 이중 운영이 필요합니다.
- 데이터가 크게 증가하면 HNSW index, connection pool과 전용 vector store를 재평가해야 합니다.
