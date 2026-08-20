package com.example.mypasture.ai.application

import com.example.mypasture.ai.domain.GroundedAnswer
import com.example.mypasture.config.AiProperties
import com.example.mypasture.user.domain.SubscriptionTier
import org.springframework.stereotype.Service
import java.util.UUID

/** 무료 사용자 일일 AI 사용량을 초과했을 때 발생하는 예외입니다. */
class AiQuotaExceededException : RuntimeException("Daily AI quota exceeded")

/** AI 입력 길이 제한을 초과했을 때 발생하는 예외입니다. */
class AiInputTooLongException : RuntimeException("AI input must be 300 characters or less")

/** RAG 검색 결과가 없어 안전하게 답변할 수 없을 때 발생하는 예외입니다. */
class AiGroundingNotFoundException : RuntimeException("No relevant Bible verses found")

class AiSafetyException : RuntimeException("지금 즉시 자신이나 다른 사람을 해칠 위험이 있다면 112 또는 119에 연락하고, 가까운 사람과 전문 상담기관의 도움을 받으세요.")
class AiProviderException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** 사용자의 구독 정책을 반영한 당일 AI 사용량입니다. null 한도는 무제한을 의미합니다. */
data class AiDailyUsage(
    val limit: Long?,
    val used: Long,
    val remaining: Long?,
    val unlimited: Boolean,
)

// AI assistant의 application layer 진입점입니다.
// 새 AI 기능(기도문 작성, 말씀 추천, 묵상 리뷰 등)은 이 유스케이스를 복사하기보다
// 입력 검증 -> quota -> cache -> RAG -> completion -> usage log 순서를 유지하면서 별도 UseCase로 확장하세요.
@Service
class AskBibleAssistantUseCase(
    private val properties: AiProperties,
    private val retrievalPort: BibleRetrievalPort,
    private val completionPort: AiCompletionPort,
    private val streamingCompletionPort: AiStreamingCompletionPort,
    private val cachePort: AiCachePort,
    private val usagePort: AiUsagePort,
) {
    /** AI 호출 전에도 모바일이 정확한 남은 횟수를 표시할 수 있게 합니다. */
    fun dailyUsage(userId: UUID, subscriptionTier: SubscriptionTier): AiDailyUsage {
        val used = usagePort.countToday(userId)
        if (subscriptionTier == SubscriptionTier.PREMIUM) {
            return AiDailyUsage(limit = null, used = used, remaining = null, unlimited = true)
        }

        val limit = properties.freeDailyLimit.toLong()
        return AiDailyUsage(
            limit = limit,
            used = used,
            remaining = (limit - used).coerceAtLeast(0),
            unlimited = false,
        )
    }

    fun ask(
        userId: UUID,
        subscriptionTier: SubscriptionTier,
        question: String,
    ): GroundedAnswer = execute(userId, subscriptionTier, question) { normalized, citations ->
        completionPort.answerWithCitations(normalized, citations)
    }

    /** RAG 검색이 끝난 뒤 LLM의 답변 조각을 생성 즉시 호출자에게 전달합니다. */
    fun stream(
        userId: UUID,
        subscriptionTier: SubscriptionTier,
        question: String,
        onDelta: (String) -> Unit,
    ): GroundedAnswer = execute(
        userId = userId,
        subscriptionTier = subscriptionTier,
        question = question,
        onCacheHit = { onDelta(it.answer) },
    ) { normalized, citations ->
        streamingCompletionPort.answerWithCitations(normalized, citations, onDelta)
    }

    private fun execute(
        userId: UUID,
        subscriptionTier: SubscriptionTier,
        question: String,
        onCacheHit: (GroundedAnswer) -> Unit = {},
        complete: (String, List<com.example.mypasture.ai.domain.BibleCitation>) -> GroundedAnswer,
    ): GroundedAnswer {
        // 모든 AI 입력 정책은 여기서 먼저 막습니다. Controller validation만 믿지 말고 use case에서도 한 번 더 방어합니다.
        val normalized = question.trim()
        if (normalized.length > properties.maxInputChars) throw AiInputTooLongException()
        if (CRISIS_KEYWORDS.any { normalized.contains(it, ignoreCase = true) }) throw AiSafetyException()

        if (subscriptionTier == SubscriptionTier.FREE && usagePort.countToday(userId) >= properties.freeDailyLimit) {
            throw AiQuotaExceededException()
        }

        // 동일 질문 비용 절감을 위한 1차 캐시입니다. 향후 semantic cache를 추가해도 이 위치에서 먼저 확인하면 됩니다.
        cachePort.get(normalized)?.let { cached ->
            val cacheHit = cached.copy(
                promptTokens = 0,
                completionTokens = 0,
                estimatedCostUsd = java.math.BigDecimal.ZERO,
                cacheHit = true,
                embeddingTokens = 0,
                embeddingCostUsd = java.math.BigDecimal.ZERO,
            )
            usagePort.save(userId, normalized, cacheHit)
            onCacheHit(cacheHit)
            return cacheHit
        }

        // RAG 강제 지점입니다. AI는 이 검색 결과가 없으면 답변하지 않아야 합니다.
        val retrieval = retrievalPort.searchRelevantVerses(normalized)
        if (retrieval.citations.isEmpty()) throw AiGroundingNotFoundException()

        // 일반 응답과 streaming 응답 모두 같은 RAG, 비용 기록, cache 정책을 통과합니다.
        val completion = complete(normalized, retrieval.citations)
        val answer = completion.copy(
            estimatedCostUsd = completion.estimatedCostUsd.add(retrieval.embeddingCostUsd),
            embeddingTokens = retrieval.embeddingTokens,
            embeddingCostUsd = retrieval.embeddingCostUsd,
            retrievalStrategy = retrieval.strategy,
        )
        usagePort.save(userId, normalized, answer)
        cachePort.put(normalized, answer)
        return answer
    }

    companion object {
        private val CRISIS_KEYWORDS = listOf("자살", "죽고 싶", "극단적 선택", "해치고 싶", "살고 싶지")
    }
}
