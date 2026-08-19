package com.example.mypasture.ai.application

import com.example.mypasture.ai.domain.BibleCitation
import com.example.mypasture.ai.domain.BibleEmbeddingSource
import com.example.mypasture.ai.domain.BibleRetrievalResult
import com.example.mypasture.ai.domain.GroundedAnswer
import com.example.mypasture.ai.domain.TextEmbeddingBatch
import java.util.UUID

/** 성경 구절 검색 구현체를 application layer와 분리하는 RAG 검색 포트입니다. */
interface BibleRetrievalPort {
    fun searchRelevantVerses(
        question: String,
        limit: Int = 10,
    ): BibleRetrievalResult
}

/** 질문과 성경 본문을 같은 embedding model과 차원으로 변환하는 외부 AI 포트입니다. */
interface TextEmbeddingPort {
    fun embed(inputs: List<String>): TextEmbeddingBatch
}

/** PostgreSQL의 성경 embedding index 상태와 멱등 upsert를 application layer에 제공합니다. */
interface BibleEmbeddingStorePort {
    fun isAvailable(): Boolean

    fun tryAcquireIndexLock(): AutoCloseable?

    fun findPendingSources(model: String, dimensions: Int, limit: Int): List<BibleEmbeddingSource>

    fun upsert(model: String, dimensions: Int, sources: List<BibleEmbeddingSource>, vectors: List<FloatArray>)

    fun countTotalVerses(): Long

    fun countIndexedVerses(model: String, dimensions: Int): Long
}

/** OpenAI 같은 LLM 호출 구현체를 application layer와 분리하는 completion 포트입니다. */
interface AiCompletionPort {
    fun answerWithCitations(
        question: String,
        citations: List<BibleCitation>,
    ): GroundedAnswer
}

/** LLM이 생성한 답변 조각을 완료 전부터 전달하는 실시간 completion 포트입니다. */
interface AiStreamingCompletionPort {
    fun answerWithCitations(
        question: String,
        citations: List<BibleCitation>,
        onDelta: (String) -> Unit,
    ): GroundedAnswer
}

/** 동일하거나 재사용 가능한 AI 질문 답변을 저장해 비용을 줄이는 캐시 포트입니다. */
interface AiCachePort {
    fun get(question: String): GroundedAnswer?

    fun put(
        question: String,
        answer: GroundedAnswer,
    )
}

/** AI 사용 횟수, 토큰, 예상 비용을 기록하고 quota 판단에 사용하는 사용량 포트입니다. */
interface AiUsagePort {
    fun countToday(userId: UUID): Long

    fun save(
        userId: UUID,
        question: String,
        answer: GroundedAnswer,
    )
}
