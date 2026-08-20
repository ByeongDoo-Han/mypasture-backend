package com.example.mypasture.ai.domain

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** RAG로 검색된 성경 구절과 출처 정보를 표현하는 값 객체입니다. */
data class BibleCitation(
    val verseId: Long,
    val version: String,
    val book: String,
    val chapter: Int,
    val verse: Int,
    val text: String,
)

/** RAG가 사용한 검색 방식으로 운영 중 vector index 누락과 lexical fallback을 구분합니다. */
enum class RagRetrievalStrategy {
    DIRECT_REFERENCE,
    PGVECTOR,
    LEXICAL_FALLBACK,
}

/** 사용자 질문의 목적을 구분해 검색 문맥과 답변 품질을 일관되게 유지합니다. */
enum class BibleQuestionIntent {
    PASTORAL_COUNSELING,
    BIBLE_EXPLANATION,
    PRAYER,
    VERSE_RECOMMENDATION,
    DEVOTIONAL,
    BIBLE_QA,
}

/** 사용자가 명시한 성경 권·장·절입니다. null 장/절은 권 전체를 의미합니다. */
data class BiblePassageReference(
    val bookCode: String,
    val bookName: String,
    val chapter: Int? = null,
    val verse: Int? = null,
)

/** 원문 질문과 RAG 검색에 최적화한 의미 질의를 분리한 application 입력 모델입니다. */
data class UnderstoodBibleQuestion(
    val originalQuestion: String,
    val searchQuery: String,
    val intent: BibleQuestionIntent,
    val lexicalKeywords: List<String>,
    val reference: BiblePassageReference? = null,
)

/** 모바일이 실제 AI 처리 순서를 사용자에게 안내할 때 사용하는 단계입니다. */
enum class AiAssistantStage {
    UNDERSTANDING_QUESTION,
    SEARCHING_SCRIPTURE,
    GENERATING_ANSWER,
}

/** 검색된 성경 근거와 질문 embedding 사용량을 completion 단계로 전달합니다. */
data class BibleRetrievalResult(
    val citations: List<BibleCitation>,
    val embeddingTokens: Int = 0,
    val embeddingCostUsd: BigDecimal = BigDecimal.ZERO,
    val strategy: RagRetrievalStrategy = RagRetrievalStrategy.LEXICAL_FALLBACK,
)

/** embedding 제공자가 반환한 순서가 보존된 vector batch와 사용량입니다. */
data class TextEmbeddingBatch(
    val vectors: List<FloatArray>,
    val promptTokens: Int,
    val estimatedCostUsd: BigDecimal,
)

/** 성경 절 하나를 embedding index에 적재하기 위한 검증된 원본입니다. */
data class BibleEmbeddingSource(
    val verseId: Long,
    val content: String,
    val contentHash: String,
)

/** 관리자와 worker가 사용하는 성경 embedding index 진행 상태입니다. */
data class BibleEmbeddingIndexSummary(
    val totalVerses: Long,
    val indexedVerses: Long,
    val processedInBatch: Int,
    val remainingVerses: Long,
    val lockAcquired: Boolean,
    val promptTokens: Int = 0,
    val estimatedCostUsd: BigDecimal = BigDecimal.ZERO,
)

/** AI 답변, 근거 구절, 토큰 사용량, 비용 정보를 함께 담는 응답 모델입니다. */
data class GroundedAnswer(
    val conversationId: UUID?,
    val answer: String,
    val citations: List<BibleCitation>,
    val promptTokens: Int,
    val completionTokens: Int,
    val estimatedCostUsd: BigDecimal,
    val cacheHit: Boolean,
    val embeddingTokens: Int = 0,
    val embeddingCostUsd: BigDecimal = BigDecimal.ZERO,
    val retrievalStrategy: RagRetrievalStrategy = RagRetrievalStrategy.LEXICAL_FALLBACK,
    val questionIntent: BibleQuestionIntent = BibleQuestionIntent.BIBLE_QA,
)

/** AI 입력 제한과 무료 사용자 사용량 제한 정책을 표현하는 도메인 모델입니다. */
data class AiUsagePolicy(
    val maxInputChars: Int,
    val freeDailyLimit: Int,
)

/** 특정 사용자의 하루 AI 사용량 집계 결과를 표현하는 도메인 모델입니다. */
data class DailyAiUsage(
    val userId: UUID,
    val dateStart: Instant,
    val count: Long,
)
