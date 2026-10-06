package com.example.mypasture.ai.application

import com.example.mypasture.ai.domain.GroundedAnswer
import com.example.mypasture.ai.domain.AiAssistantStage
import com.example.mypasture.ai.domain.AiRuntimeConfigSnapshot
import com.example.mypasture.ai.domain.AiAssistantExperiencePolicy
import com.example.mypasture.ai.domain.AiConversationContext
import com.example.mypasture.ai.domain.AiConversationTurn
import com.example.mypasture.ai.domain.AiClientRequest
import com.example.mypasture.ai.domain.AiClientRequestStatus
import com.example.mypasture.ai.domain.AiRequestInProgressException
import com.example.mypasture.ai.domain.AiRequestNotFoundException
import com.example.mypasture.ai.domain.AiRequestPreviouslyFailedException
import com.example.mypasture.ai.domain.AiRequestResultExpiredException
import com.example.mypasture.ai.domain.AiUsageReservation
import com.example.mypasture.ai.domain.BibleRetrievalResult
import com.example.mypasture.ai.domain.BibleQuestionIntent
import com.example.mypasture.config.AiProperties
import com.example.mypasture.user.domain.SubscriptionTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import java.util.UUID
import java.time.Instant

/** 무료 사용자 일일 AI 사용량을 초과했을 때 발생하는 예외입니다. */
class AiQuotaExceededException : RuntimeException("Daily AI quota exceeded")

/** AI 입력 길이 제한을 초과했을 때 발생하는 예외입니다. */
class AiInputTooLongException : RuntimeException("AI input must be 300 characters or less")

/** RAG 검색 결과가 없어 안전하게 답변할 수 없을 때 발생하는 예외입니다. */
class AiGroundingNotFoundException : RuntimeException("No relevant Bible verses found")

class AiSafetyException : RuntimeException("지금 즉시 자신이나 다른 사람을 해칠 위험이 있다면 112 또는 119에 연락하고, 가까운 사람과 전문 상담기관의 도움을 받으세요.")
open class AiProviderException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** 사용자의 구독 정책을 반영한 당일 AI 사용량입니다. null 한도는 무제한을 의미합니다. */
data class AiDailyUsage(
    val limit: Long?,
    val used: Long,
    val remaining: Long?,
    val unlimited: Boolean,
    val effectiveTier: SubscriptionTier,
    val entitlementExpiresAt: Instant?,
)

// AI assistant의 application layer 진입점입니다.
// 새 AI 기능(기도문 작성, 말씀 추천, 묵상 리뷰 등)은 이 유스케이스를 복사하기보다
// 입력 검증 -> quota -> cache -> RAG -> completion -> usage log 순서를 유지하면서 별도 UseCase로 확장하세요.
@Service
class AskBibleAssistantUseCase(
    private val properties: AiProperties,
    private val questionUnderstandingService: BibleQuestionUnderstandingService,
    private val retrievalPort: BibleRetrievalPort,
    private val qualityService: AiAnswerQualityService,
    private val streamingCompletionPort: AiStreamingCompletionPort,
    private val cachePort: AiCachePort,
    private val usagePort: AiUsagePort,
    private val runtimeConfigService: AiRuntimeConfigService,
    private val experiencePolicyResolver: AiAssistantExperiencePolicyResolver,
    private val conversationPort: AiConversationPort,
    private val accessPolicyResolver: AiAccessPolicyResolver,
    private val scopeService: AiQuestionScopeService,
) {
    /** AI 호출 전에도 모바일이 정확한 남은 횟수를 표시할 수 있게 합니다. */
    fun dailyUsage(userId: UUID, subscriptionTier: SubscriptionTier): AiDailyUsage {
        val used = usagePort.countToday(userId)
        val accessPolicy = accessPolicyResolver.resolve(subscriptionTier)
        if (accessPolicy.unlimited) {
            return AiDailyUsage(null, used, null, true, accessPolicy.effectiveTier, accessPolicy.entitlementExpiresAt)
        }
        val limit = accessPolicy.reservationDailyLimit
        return AiDailyUsage(
            limit = limit,
            used = used,
            remaining = (limit - used).coerceAtLeast(0),
            unlimited = false,
            effectiveTier = accessPolicy.effectiveTier,
            entitlementExpiresAt = accessPolicy.entitlementExpiresAt,
        )
    }

    fun ask(
        userId: UUID,
        subscriptionTier: SubscriptionTier,
        question: String,
        conversationId: UUID? = null,
        clientRequestId: UUID? = null,
    ): GroundedAnswer = runBlocking { askAsync(userId, subscriptionTier, question, conversationId, clientRequestId) }

    /** HTTP 요청 스레드를 점유하지 않고 외부 AI 응답을 기다리는 coroutine 진입점입니다. */
    suspend fun askAsync(
        userId: UUID,
        subscriptionTier: SubscriptionTier,
        question: String,
        conversationId: UUID? = null,
        clientRequestId: UUID? = null,
    ): GroundedAnswer = execute(userId, subscriptionTier, question, conversationId, clientRequestId) { normalized, citations, config, policy, context ->
        qualityService.generate(normalized, citations, config, policy, context)
    }

    /** RAG 검색이 끝난 뒤 LLM의 답변 조각을 생성 즉시 호출자에게 전달합니다. */
    fun stream(
        userId: UUID,
        subscriptionTier: SubscriptionTier,
        question: String,
        conversationId: UUID? = null,
        clientRequestId: UUID? = null,
        onStage: (AiAssistantStage) -> Unit = {},
        onDelta: (String) -> Unit,
    ): GroundedAnswer = runBlocking {
        streamAsync(userId, subscriptionTier, question, conversationId, clientRequestId, onStage) { onDelta(it) }
    }

    /** SSE scope의 취소와 timeout을 외부 AI 호출까지 전달하는 coroutine 스트리밍 진입점입니다. */
    suspend fun streamAsync(
        userId: UUID,
        subscriptionTier: SubscriptionTier,
        question: String,
        conversationId: UUID? = null,
        clientRequestId: UUID? = null,
        onStage: (AiAssistantStage) -> Unit = {},
        onDelta: suspend (String) -> Unit,
    ): GroundedAnswer = execute(
        userId = userId,
        subscriptionTier = subscriptionTier,
        question = question,
        conversationId = conversationId,
        clientRequestId = clientRequestId,
        onStage = onStage,
        onCacheHit = { onDelta(it.answer) },
    ) { normalized, citations, config, policy, context ->
        if (config.evaluationEnabled) {
            qualityService.generate(normalized, citations, config, policy, context) {
                onStage(AiAssistantStage.REVIEWING_ANSWER)
            }.also { answer ->
                answer.answer.chunked(24).forEach { onDelta(it) }
            }
        } else {
            streamingCompletionPort.answerWithCitations(normalized, citations, config, policy, context, onDelta)
        }
    }

    private suspend fun execute(
        userId: UUID,
        subscriptionTier: SubscriptionTier,
        question: String,
        conversationId: UUID?,
        clientRequestId: UUID?,
        onStage: (AiAssistantStage) -> Unit = {},
        onCacheHit: suspend (GroundedAnswer) -> Unit = {},
        complete: suspend (
            String,
            List<com.example.mypasture.ai.domain.BibleCitation>,
            AiRuntimeConfigSnapshot,
            AiAssistantExperiencePolicy,
            AiConversationContext?,
        ) -> GroundedAnswer,
    ): GroundedAnswer = withContext(Dispatchers.IO) {
        // 모든 AI 입력 정책은 여기서 먼저 막습니다. Controller validation만 믿지 말고 use case에서도 한 번 더 방어합니다.
        val normalized = question.trim()
        if (normalized.length > properties.maxInputChars) throw AiInputTooLongException()
        if (CRISIS_KEYWORDS.any { normalized.contains(it, ignoreCase = true) }) throw AiSafetyException()

        val accessPolicy = accessPolicyResolver.resolve(subscriptionTier)
        val effectiveTier = accessPolicy.effectiveTier
        val experiencePolicy = experiencePolicyResolver.resolve(effectiveTier)
        if (conversationId != null && effectiveTier != SubscriptionTier.PREMIUM) {
            throw com.example.mypasture.ai.domain.AiConversationUnavailableException()
        }
        val conversationContext = conversationId?.let { conversationPort.load(userId, it) }

        val dailyLimit = accessPolicy.reservationDailyLimit
        val runtimeConfig = runtimeConfigService.current()
        val fingerprint = "$normalized\nconversationId=${conversationId ?: "none"}"
        val reservation = usagePort.reserve(
            userId = userId,
            question = normalized,
            dailyLimit = dailyLimit,
            config = runtimeConfig,
            clientRequestId = clientRequestId,
            requestFingerprint = fingerprint,
        ) ?: throw AiQuotaExceededException()
        val reservationId = when (reservation) {
            is AiUsageReservation.Started -> reservation.turnId
            is AiUsageReservation.Existing -> return@withContext replayExisting(reservation.request, onCacheHit)
        }
        var consumedScope: com.example.mypasture.ai.domain.AiQuestionScopeAssessment? = null
        var consumedRetrieval: BibleRetrievalResult? = null
        var consumedIntent = BibleQuestionIntent.BIBLE_QA
        var consumedCompletion: GroundedAnswer? = null

        try {
            onStage(AiAssistantStage.UNDERSTANDING_QUESTION)

            consumedScope = scopeService.assess(normalized, conversationContext, runtimeConfig)
            scopeService.requireSupported(consumedScope)

            // 상담 내용이 다른 사용자에게 재사용되지 않도록 사용자별 HMAC cache를 사용합니다.
            val cacheScope = "${runtimeConfig.cacheScope}:${experiencePolicy.interactionMode}:$AI_RESPONSE_CACHE_VERSION:${com.example.mypasture.ai.domain.AI_PASTOR_VERSION}"
            if (conversationContext == null) cachePort.get(userId, normalized, cacheScope)?.let { cached ->
                val cacheHit = cached.copy(
                    turnId = reservationId,
                    promptTokens = 0,
                    completionTokens = 0,
                    estimatedCostUsd = java.math.BigDecimal.ZERO,
                    cacheHit = true,
                    embeddingTokens = 0,
                    embeddingCostUsd = java.math.BigDecimal.ZERO,
                    evaluationPromptTokens = 0,
                    evaluationCompletionTokens = 0,
                    evaluationCostUsd = java.math.BigDecimal.ZERO,
                ).withScopeUsage(consumedScope)
                usagePort.complete(reservationId, cacheHit, normalized, runtimeConfig.version)
                onCacheHit(cacheHit)
                return@withContext cacheHit
            }

            val retrievalQuestion = conversationContext?.retrievalQuestion(normalized) ?: normalized
            val understoodQuestion = questionUnderstandingService.understand(retrievalQuestion)
            consumedIntent = understoodQuestion.intent

            // RAG 강제 지점입니다. AI는 DB 검색 결과가 없으면 답변하지 않아야 합니다.
            onStage(AiAssistantStage.SEARCHING_SCRIPTURE)
            val retrieval = retrievalPort.searchRelevantVerses(understoodQuestion, experiencePolicy.scriptureCount)
            consumedRetrieval = retrieval
            if (retrieval.citations.isEmpty()) throw AiGroundingNotFoundException()

            // 일반 응답과 streaming 응답 모두 같은 RAG, 비용 기록, cache 정책을 통과합니다.
            onStage(AiAssistantStage.GENERATING_ANSWER)
            val completion = complete(normalized, retrieval.citations, runtimeConfig, experiencePolicy, conversationContext)
            consumedCompletion = completion
            var answer = completion.copy(
                turnId = reservationId,
                estimatedCostUsd = completion.estimatedCostUsd.add(retrieval.embeddingCostUsd),
                embeddingTokens = retrieval.embeddingTokens,
                embeddingCostUsd = retrieval.embeddingCostUsd,
                retrievalStrategy = retrieval.strategy,
                questionIntent = understoodQuestion.intent,
                interactionMode = experiencePolicy.interactionMode,
            )
            answer = answer.withScopeUsage(consumedScope)
            if (effectiveTier == SubscriptionTier.PREMIUM) {
                val savedConversationId = conversationPort.append(
                    userId = userId,
                    conversationId = conversationContext?.conversationId,
                    turn = AiConversationTurn(
                        userQuestion = normalized,
                        assistantAnswer = answer.answer,
                        followUpQuestion = answer.followUpQuestion?.question,
                    ),
                    maxContextTurns = experiencePolicy.maxContextTurns,
                )
                answer = answer.copy(conversationId = savedConversationId)
            }
            usagePort.complete(reservationId, answer, normalized, runtimeConfig.version)
            if (conversationContext == null && effectiveTier == SubscriptionTier.FREE) {
                cachePort.put(userId, normalized, cacheScope, answer)
            }
            return@withContext answer
        } catch (error: RuntimeException) {
            val rejectedAnswer = (error as? AiAnswerRejectedException)?.rejectedAnswer
            val scope = (error as? AiQuestionScopeProviderException)?.assessment ?: consumedScope
            val consumedAnswer = consumedAiUsage(rejectedAnswer ?: consumedCompletion, scope, consumedRetrieval)
                ?.copy(questionIntent = consumedIntent)
            usagePort.fail(reservationId, error.aiFailureCode("AI_REQUEST_FAILED"), consumedAnswer)
            throw error
        }
    }

    /** 연결이 끊긴 클라이언트가 같은 요청 ID로 최종 상태를 다시 확인합니다. */
    fun requestStatus(userId: UUID, clientRequestId: UUID): AiClientRequest =
        usagePort.findClientRequest(userId, clientRequestId) ?: throw AiRequestNotFoundException()

    private suspend fun replayExisting(
        request: AiClientRequest,
        onCompleted: suspend (GroundedAnswer) -> Unit,
    ): GroundedAnswer = when (request.status) {
        AiClientRequestStatus.PROCESSING -> throw AiRequestInProgressException()
        AiClientRequestStatus.FAILED -> throw AiRequestPreviouslyFailedException()
        AiClientRequestStatus.EXPIRED -> throw AiRequestResultExpiredException()
        AiClientRequestStatus.COMPLETED -> requireNotNull(request.answer).also { onCompleted(it) }
    }

    companion object {
        private val CRISIS_KEYWORDS = listOf("자살", "죽고 싶", "극단적 선택", "해치고 싶", "살고 싶지")
        private const val AI_RESPONSE_CACHE_VERSION = "assistant-response-v3-scope"
    }
}
