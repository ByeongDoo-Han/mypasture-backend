# AI 목자 pgvector RAG 운영 가이드

## 요청 흐름

AI 목자 요청은 모바일에 저장된 성경이 아니라 서버의 검증된 성경 DB와 pgvector index를 사용합니다.

1. Redis에서 동일 질문 답변을 확인합니다.
2. 질문을 `OPENAI_EMBEDDING_MODEL`로 embedding 합니다.
3. 같은 모델과 차원의 `bible_embeddings`에서 cosine similarity Top-K를 조회합니다.
4. `bible_verses`, `bible_books`와 조인한 실제 절 ID와 본문만 RAG context로 만듭니다.
5. LLM 응답의 `[verseId]`와 장·절을 streaming 전송 직전에 검증합니다.
6. embedding, prompt, completion 토큰과 각각의 예상 비용을 저장합니다.

전체 절의 index가 준비되기 전에는 일부 데이터만으로 vector 검색하지 않습니다. 로컬은 명시적인 lexical
fallback을 사용할 수 있지만 운영 기본값은 fallback 비활성화이며 `AI_GROUNDING_NOT_FOUND`를 반환합니다.

## 최초 index 생성

성경 DB 적재가 끝난 뒤 실제 OpenAI API Key를 설정합니다.

```dotenv
AI_MOCK_ENABLED=false
OPENAI_EMBEDDING_MODEL=text-embedding-3-small
AI_EMBEDDING_DIMENSIONS=1536
AI_EMBEDDING_BATCH_SIZE=100
AI_EMBEDDING_INDEXING_ENABLED=true
AI_RAG_LEXICAL_FALLBACK_ENABLED=false
```

`AI_EMBEDDING_INDEXING_ENABLED=true`는 백필을 담당하는 서버 한 대에만 설정합니다. worker는 PostgreSQL
advisory lock을 획득하고 한 번에 설정된 수만큼 처리하므로 재시작과 중복 실행에 안전합니다.

관리자 API로 진행률과 수동 단일 batch를 확인할 수 있습니다.

```http
GET  /api/v1/admin/ai/embeddings
POST /api/v1/admin/ai/embeddings/index-next-batch
```

`indexedVerses == totalVerses`, `remainingVerses == 0`이 되면 자동 worker 설정을 다시 `false`로 변경합니다.
본문이 수정되면 DB trigger가 해당 절 embedding을 삭제하며, 다음 worker batch가 다시 생성합니다.

## 모델과 차원 변경

`bible_embeddings`의 현재 Flyway schema는 `vector(1536)`입니다. 모델만 바꾸는 경우 새 모델명으로 별도
행을 적재한 뒤 트래픽 설정을 전환할 수 있습니다. 차원을 바꾸려면 먼저 새 vector column 또는 새 index
table을 추가하는 Flyway migration과 이중 읽기 기간을 준비해야 합니다.

OpenAI Embeddings API는 문자열 배열 입력과 `dimensions` 옵션을 지원합니다. 요청 배열과 토큰 한도를
넘지 않도록 batch 크기를 제한하며, 운영 기본값은 100입니다.

## 장애 정책

- OpenAI embedding 호출은 timeout과 최대 3회 retry를 적용합니다.
- Redis 장애는 캐시 없이 계속 진행하지만 pgvector 또는 embedding 장애는 운영에서 숨기지 않습니다.
- 검색 근거가 없거나 LLM citation 검증에 실패하면 답변을 저장하거나 캐싱하지 않습니다.
- API Key는 서버 Secret Manager에만 두고 모바일 앱과 관리자 프론트에는 전달하지 않습니다.
- 질문 원문은 usage log에 저장하지 않고 SHA-256 hash만 저장합니다.

## 실시간 응답

`POST /api/v1/ai/chat/stream`은 OpenAI Responses API의 `response.output_text.delta`를 SSE로 전달합니다.
답변은 첫 번째 유효한 `[verseId]`가 확인될 때까지 잠시 보류하며, 검색 결과에 없는 ID나 장·절은
클라이언트에 전송하기 전에 차단합니다. 정상 완료 이벤트 순서는 다음과 같습니다.

```text
event: delta
data: 근거: [123] ...

event: citations
data: [{"verseId":123,...}]

event: usage
data: {"promptTokens":100,"completionTokens":80,"retrievalStrategy":"PGVECTOR",...}

event: done
data: {"status":"completed"}
```

`AI_STREAM_TIMEOUT_MS`는 전체 응답 제한 시간이며, 전용 thread pool 크기는
`AI_STREAM_CORE_POOL_SIZE`, `AI_STREAM_MAX_POOL_SIZE`, `AI_STREAM_QUEUE_CAPACITY`로 제한합니다.
429 또는 5xx와 연결 오류는 delta 전까지만 최대 3회 재시도합니다. 일부 문자를 전송한 뒤에는 중복
표시를 막기 위해 자동 재시도하지 않습니다.
