package com.example.mypasture.ai.application

import com.example.mypasture.ai.domain.AiAnswerFeedback
import com.example.mypasture.ai.domain.AiFeedbackInvalidException
import com.example.mypasture.ai.domain.AiFeedbackRating
import com.example.mypasture.ai.domain.AiFeedbackReason
import com.example.mypasture.ai.domain.AiFeedbackRegenerationUnavailableException
import com.example.mypasture.ai.domain.AiFeedbackSummary
import com.example.mypasture.ai.domain.GroundedAnswer
import com.example.mypasture.config.AiProperties
import com.example.mypasture.user.domain.SubscriptionTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import java.util.UUID

/** 사용자 평가를 저장하고 동일 질문을 개선 근거로 한 번 무료 재생성합니다. */
@Service
class AiAnswerFeedbackService(
    private val properties: AiProperties,
    private val feedbackPort: AiFeedbackPort,
    private val usagePort: AiUsagePort,
    private val questionUnderstandingService: BibleQuestionUnderstandingService,
    private val retrievalPort: BibleRetrievalPort,
    private val qualityService: AiAnswerQualityService,
    private val runtimeConfigService: AiRuntimeConfigService,
    private val experiencePolicyResolver: AiAssistantExperiencePolicyResolver,
    private val accessPolicyResolver: AiAccessPolicyResolver,
) {
    fun submit(
        userId: UUID,
        turnId: UUID,
        rating: AiFeedbackRating,
        reasons: Set<AiFeedbackReason>,
        comment: String?,
    ): AiAnswerFeedback {
        validateFeedback(rating, reasons, comment)
        return feedbackPort.upsert(userId, turnId, rating, reasons, comment?.trim())
    }

    suspend fun regenerate(
        userId: UUID,
        subscriptionTier: SubscriptionTier,
        turnId: UUID,
        question: String,
    ): GroundedAnswer = withContext(Dispatchers.IO) {
        val normalized = question.trim()
        if (normalized.isBlank() || normalized.length > properties.maxInputChars) throw AiInputTooLongException()
        val source = usagePort.findCompletedTurn(userId, turnId) ?: throw AiFeedbackRegenerationUnavailableException()
        if (!usagePort.questionMatches(userId, turnId, normalized)) throw AiFeedbackRegenerationUnavailableException()

        val feedback = feedbackPort.beginRegeneration(userId, turnId)
        var reservationId: UUID? = null
        var consumed: GroundedAnswer? = null

        try {
            val runtimeConfig = runtimeConfigService.current()
            val effectiveTier = accessPolicyResolver.resolve(userId, subscriptionTier).effectiveTier
            val experiencePolicy = experiencePolicyResolver.resolve(effectiveTier)
            val activeReservationId = usagePort.reserveRegeneration(userId, normalized, turnId, runtimeConfig)
            reservationId = activeReservationId
            val understood = questionUnderstandingService.understand(normalized)
            val retrieval = retrievalPort.searchRelevantVerses(understood, experiencePolicy.scriptureCount + EXTRA_RAG_CANDIDATES)
            if (retrieval.citations.isEmpty()) throw AiGroundingNotFoundException()
            val citations = if (feedback.reasons.any(ALTERNATIVE_SCRIPTURE_REASONS::contains)) {
                retrieval.citations.filterNot { it.verseId in source.citationVerseIds }
                    .take(experiencePolicy.scriptureCount)
                    .ifEmpty { retrieval.citations.take(experiencePolicy.scriptureCount) }
            } else {
                retrieval.citations.take(experiencePolicy.scriptureCount)
            }
            if (citations.isEmpty()) throw AiGroundingNotFoundException()

            val completion = qualityService.generate(
                question = normalized,
                citations = citations,
                config = runtimeConfig,
                experiencePolicy = experiencePolicy,
                generationInstructions = feedback.reasons.flatMap(::serverInstruction).distinct(),
            )
            val answer = completion.copy(
                conversationId = null,
                turnId = activeReservationId,
                estimatedCostUsd = completion.estimatedCostUsd.add(retrieval.embeddingCostUsd),
                embeddingTokens = retrieval.embeddingTokens,
                embeddingCostUsd = retrieval.embeddingCostUsd,
                retrievalStrategy = retrieval.strategy,
                questionIntent = understood.intent,
                interactionMode = experiencePolicy.interactionMode,
            )
            consumed = answer
            usagePort.complete(activeReservationId, answer)
            feedbackPort.completeRegeneration(userId, turnId, activeReservationId)
            answer
        } catch (error: RuntimeException) {
            reservationId?.let { usagePort.fail(it, error::class.simpleName ?: "AI_REGENERATION_FAILED", consumed) }
            feedbackPort.failRegeneration(userId, turnId)
            throw error
        }
    }

    fun summary(): AiFeedbackSummary = feedbackPort.summary()

    fun priority(limit: Int): List<AiAnswerFeedback> = feedbackPort.priority(limit)

    private fun validateFeedback(
        rating: AiFeedbackRating,
        reasons: Set<AiFeedbackReason>,
        comment: String?,
    ) {
        if (comment != null && comment.trim().length > MAX_COMMENT_CHARS) {
            throw AiFeedbackInvalidException("피드백 의견은 300자 이하여야 합니다.")
        }
        when (rating) {
            AiFeedbackRating.HELPFUL -> if (reasons.isNotEmpty()) {
                throw AiFeedbackInvalidException("도움됨 평가에는 아쉬운 이유를 선택할 수 없습니다.")
            }
            AiFeedbackRating.NOT_HELPFUL -> if (reasons.isEmpty() || reasons.size > MAX_REASON_COUNT) {
                throw AiFeedbackInvalidException("아쉬움 평가는 이유를 1개 이상 3개 이하로 선택해야 합니다.")
            }
        }
    }

    private fun serverInstruction(reason: AiFeedbackReason): List<String> = when (reason) {
        AiFeedbackReason.MISUNDERSTOOD_QUESTION -> listOf("질문의 핵심 감정과 요청을 먼저 한 문장으로 정확히 짚으세요.")
        AiFeedbackReason.IRRELEVANT_SCRIPTURE -> listOf("질문의 주제와 직접 연결되는 RAG 근거만 사용하고 연결 이유를 설명하세요.")
        AiFeedbackReason.TOO_GENERIC -> listOf("상투적인 표현을 줄이고 오늘 실행할 수 있는 구체적인 한 가지를 제안하세요.")
        AiFeedbackReason.HARMFUL_OR_OVERCONFIDENT -> listOf("단정하거나 정죄하지 말고 한계를 밝히며 필요한 경우 사람의 도움을 권하세요.")
        AiFeedbackReason.WRONG_OR_NONEXISTENT_SCRIPTURE -> listOf("RAG_CONTEXT의 verseId와 본문만 인용하고 장절이나 문장을 새로 만들지 마세요.")
        AiFeedbackReason.OTHER -> listOf("질문에 더 직접적이고 간결하게 답하세요.")
    }

    companion object {
        private const val MAX_COMMENT_CHARS = 300
        private const val MAX_REASON_COUNT = 3
        private const val EXTRA_RAG_CANDIDATES = 4
        private val ALTERNATIVE_SCRIPTURE_REASONS = setOf(
            AiFeedbackReason.IRRELEVANT_SCRIPTURE,
            AiFeedbackReason.WRONG_OR_NONEXISTENT_SCRIPTURE,
        )
    }
}
