# 성경 장별 AI 해설 생성 및 게시 운영 가이드

## 목적

사용자가 성경 장을 열 때마다 AI를 호출하지 않는다. 관리자가 생성 작업을 등록하면 백그라운드 워커가 초안을 만들고, 자동 검증과 관리자 검수를 통과한 최신 `PUBLISHED` 해설만 공개 API가 반환한다.

## 상태 흐름

```text
생성 작업: PENDING -> PROCESSING -> COMPLETED | FAILED
해설: DRAFT -> PUBLISHED -> SUPERSEDED
                  \-> REJECTED
```

`DRAFT`는 사용자에게 절대 노출하지 않는다. 같은 장의 새 개정본을 게시하면 기존 `PUBLISHED` 해설은 `SUPERSEDED`로 보존한다.

## 생성 작업 실행

운영 환경에서 다음 값을 명시적으로 설정해야 워커가 동작한다.

```text
AI_COMMENTARY_GENERATION_ENABLED=true
AI_COMMENTARY_PROMPT_VERSION=chapter-commentary-v1
AI_COMMENTARY_POLL_DELAY_MS=5000
AI_COMMENTARY_MAX_OUTPUT_TOKENS=1800
AI_COMMENTARY_INPUT_COST_PER_MILLION=0.25
AI_COMMENTARY_OUTPUT_COST_PER_MILLION=2.00
```

모델 가격이 바뀌면 비용 단가 환경 변수도 함께 변경한다. 로컬과 테스트 환경에서는 기본값 `false`를 유지해 의도하지 않은 비용이 발생하지 않게 한다.

## 관리자 API

모든 관리자 API는 `ROLE_ADMIN` JWT가 필요하다.

```http
POST /api/v1/admin/bible-commentaries/generation-jobs
Content-Type: application/json

{
  "version": "KOR1910",
  "bookCode": "GEN",
  "chapter": 1
}
```

```http
GET /api/v1/admin/bible-commentaries/generation-jobs
GET /api/v1/admin/bible-commentaries
POST /api/v1/admin/bible-commentaries/{commentaryId}/publish
POST /api/v1/admin/bible-commentaries/{commentaryId}/reject
```

게시 요청에는 `1..100` 범위의 품질 점수를 전달한다.

## 공개 API

```http
GET /api/v1/bibles/{version}/books/{bookCode}/chapters/{chapter}/commentary
```

게시된 해설이 없으면 `404 BIBLE_COMMENTARY_NOT_FOUND`를 반환한다. 모바일은 이 경우 실시간 생성 요청을 보내지 않고 준비 중 상태를 표시한다.

## 자동 검증

- 요약, 배경, 주제, 묵상 질문의 필수 개수와 길이를 검사한다.
- 구간 해설이 1절부터 마지막 절까지 중복과 누락 없이 이어지는지 검사한다.
- 모든 교차 참조 장·절을 1910 성경 원문 저장소에서 다시 조회한다.
- 성경 원문은 AI 응답에 저장하지 않고 위치만 저장한다.
- 입력 원문 SHA-256, 모델, 프롬프트 버전, 토큰, 예상 비용을 보존한다.

자동 검증은 신학 검수를 대신하지 않는다. 역사적 주장, 논쟁적인 해석, 목회적으로 민감한 적용은 관리자가 확인한 뒤 게시한다.

## 초기 생성 순서

1. 창세기 1장, 시편 23편, 마태복음 5장 등 유형이 다른 20개 장을 생성한다.
2. 실패 원인과 검수 의견을 바탕으로 프롬프트 버전을 고정한다.
3. 신약, 시가서, 역사서, 예언서 순으로 범위를 늘린다.
4. 모델이나 프롬프트 변경 시 기존 내용을 덮어쓰지 않고 새 개정본을 생성한다.
5. 오류 신고가 접수된 해설은 새 개정본을 게시하기 전까지 필요하면 비공개 처리한다.
