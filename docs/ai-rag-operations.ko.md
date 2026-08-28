# AI 목자 pgvector RAG 운영 가이드

## 요청 흐름

AI 목자 요청은 모바일에 저장된 성경이 아니라 서버의 검증된 성경 DB와 pgvector index를 사용합니다.

1. Redis에서 동일 질문 답변을 확인합니다.
2. 질문을 상담, 본문 설명, 기도문, 말씀 추천, 묵상, 일반 Q&A 의도로 분류합니다.
3. 사용자가 권·장·절을 적었다면 실제 DB 위치를 해석하고, 그 외 질문은 의도별 의미 검색 문장으로 정제합니다.
4. 검색 문장을 `OPENAI_EMBEDDING_MODEL`로 embedding 합니다.
5. 같은 모델과 차원의 `bible_embeddings`에서 cosine similarity Top-K를 조회합니다. 명시한 권·장은 검색 범위로 적용합니다.
6. `bible_verses`, `bible_books`와 조인한 실제 절 ID와 본문만 RAG context로 만듭니다.
7. LLM 응답의 `[verseId]`와 장·절을 streaming 전송 직전에 검증합니다.
8. 질문 의도, 검색 전략, embedding, prompt, completion 토큰과 각각의 예상 비용을 저장합니다.

질문 이해 단계는 규칙 기반 의도 분류와 embedding 의미 검색을 조합합니다. 별도의 GPT 호출로 검색어를 만들지
않으므로 사용자 요청당 호출 수, 지연 시간과 비용을 늘리지 않으며, GPT가 존재하지 않는 장·절을 검색 조건으로
만드는 위험도 피합니다.

기본 생성 모델은 `gpt-5.6-terra`, 추론 강도는 `low`이며 평가 모델은 `gpt-5.6-luna`, 추론 강도는
`medium`입니다. AI 목자는 검색된 구절을 짧게 설명하므로 생성 단계의 불필요한 지연과 추론 토큰을 줄이고,
평가 단계는 근거와 인용 오류를 구조화해 판별할 수 있는 수준으로 둡니다.

전체 절의 index가 준비되기 전에는 일부 데이터만으로 vector 검색하지 않습니다. 로컬은 명시적인 lexical
fallback을 사용할 수 있지만 운영 기본값은 fallback 비활성화이며 `AI_GROUNDING_NOT_FOUND`를 반환합니다.

## 성경 적재

Docker 이미지에는 `data/kor_html`의 1910 성경 원문이 `/app/data/kor_html`로 포함됩니다. Cloud Run
`migration` Job은 Flyway 완료 후 원문을 파싱해 다음 기준으로 멱등 적재합니다.

- 성경 66권
- 전체 1,189장
- 원문이 있는 1,188장과 30,991절
- 원본 파일이 없는 베드로전서 5장은 정책에 따라 빈 장(`EMPTY`)으로 저장

같은 원문을 다시 실행하면 장별 checksum을 비교해 변경된 장만 동기화하고 기존 `verseId`를 유지합니다.
여러 Job이 겹치면 PostgreSQL advisory lock으로 한 실행만 적재합니다. 원문에서 기존 절이 사라져 스크랩이나
말씀 카드 참조가 깨질 수 있는 변경은 자동 삭제하지 않고 실패시킵니다.

```bash
gcloud run jobs execute mypasture-staging-migration \
  --region=asia-northeast3 --wait
```

## 최초 index 생성

성경 DB 적재가 끝난 뒤 실제 OpenAI API Key를 설정합니다.

```dotenv
AI_MOCK_ENABLED=false
OPENAI_EMBEDDING_MODEL=text-embedding-3-small
AI_EMBEDDING_DIMENSIONS=1536
AI_EMBEDDING_BATCH_SIZE=100
AI_EMBEDDING_RESPONSE_MAX_BYTES=16777216
AI_EMBEDDING_BACKFILL_ENABLED=true
AI_EMBEDDING_BACKFILL_MAX_BATCHES=1000
AI_RAG_LEXICAL_FALLBACK_ENABLED=false
```

운영 최초 백필은 상시 worker가 아니라 비용 발생 시점을 명확히 구분한 Cloud Run Job으로 실행합니다.

```bash
gcloud run jobs execute mypasture-staging-embedding-backfill \
  --region=asia-northeast3 --wait
```

Job은 PostgreSQL advisory lock을 획득하고 `AI_EMBEDDING_BATCH_SIZE`만큼 반복 처리합니다. 각 절의 본문
hash, embedding 모델과 차원을 기준으로 이미 처리된 행을 건너뛰므로 재실행에 안전합니다. 다만 OpenAI 응답
직후 DB 저장 전에 프로세스가 종료되면 해당 batch API 비용은 한 번 더 발생할 수 있습니다.

1,536차원 vector 여러 개를 담은 JSON은 Spring WebClient 기본 256KB를 넘으므로 embedding 전용 client만
`AI_EMBEDDING_RESPONSE_MAX_BYTES`를 적용합니다. 기본값은 16MB이고 허용 범위는 256KB~64MB입니다.
전역 WebClient buffer를 높이지 않아 다른 외부 API가 큰 응답으로 서버 메모리를 점유하는 범위를 넓히지 않습니다.

빈 성경 DB, 잠금 충돌, 진행 정체, `AI_EMBEDDING_BACKFILL_MAX_BATCHES` 초과는 성공처럼 보이지 않도록 Job을
실패시킵니다. 최종 로그에는 처리 절 수, prompt token과 예상 비용 합계를 남깁니다. 1910 성경 전체의 정확한
비용은 실제 tokenizer 결과로 확정되며 `text-embedding-3-small` 입력 단가와 저장된 token 수로 계산합니다.

관리자 API로 진행률과 수동 단일 batch를 확인할 수 있습니다.

```http
GET  /api/v1/admin/ai/embeddings
POST /api/v1/admin/ai/embeddings/index-next-batch
```

`indexedVerses == totalVerses`, `remainingVerses == 0`이어야 전체 vector 검색이 활성화됩니다. 이후 원문 수정분을
상시 자동 보충할 필요가 있을 때만 `worker_enable_embedding_indexing=true`를 별도 승인해 켭니다.
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

`POST /api/v1/ai/chat/stream`은 평가 기능이 꺼진 경우 OpenAI Responses API의 `response.output_text.delta`를
SSE로 전달합니다. 평가 기능이 켜진 기본 운영 설정에서는 생성 답변 전체를 서버에 보류하고 평가·최대 1회
수정을 마친 최종본만 작은 delta로 전달합니다. 검증 전 답변은 한번 전송하면 취소할 수 없기 때문입니다.
정상 완료 이벤트 순서는 다음과 같습니다.

```text
event: status
data: {"stage":"UNDERSTANDING_QUESTION"}

event: status
data: {"stage":"REVIEWING_ANSWER"}

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
