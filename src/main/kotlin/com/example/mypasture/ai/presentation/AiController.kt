package com.example.mypasture.ai.presentation

import com.example.mypasture.ai.application.AiProviderException
import com.example.mypasture.ai.application.AiGroundingNotFoundException
import com.example.mypasture.ai.application.AiInputTooLongException
import com.example.mypasture.ai.application.AiQuotaExceededException
import com.example.mypasture.ai.application.AiSafetyException
import com.example.mypasture.ai.application.AiAnswerRejectedException
import com.example.mypasture.ai.application.AskBibleAssistantUseCase
import com.example.mypasture.ai.application.AiAnswerFeedbackService
import com.example.mypasture.ai.domain.BibleCitation
import com.example.mypasture.ai.domain.GroundedAnswer
import com.example.mypasture.ai.domain.AiAssistantStage
import com.example.mypasture.ai.domain.AI_ASSISTANT_RESPONSE_SCHEMA_VERSION
import com.example.mypasture.ai.domain.AiAssistantNextAction
import com.example.mypasture.ai.domain.AiConversationUnavailableException
import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.common.error.ApiErrorResponse
import com.example.mypasture.config.AiProperties
import com.example.mypasture.config.AiStreamingCoroutineRunner
import jakarta.validation.constraints.Size
import jakarta.validation.constraints.NotNull
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import com.example.mypasture.policy.application.PolicyConsentService
import com.example.mypasture.policy.domain.PolicyScope

/** AI 목자에게 전달할 최대 300자의 사용자 질문입니다. */
data class AskAiRequest(
    @field:Size(min = 1, max = 300) val question: String,
    val conversationId: UUID? = null,
)

/** 답변별 최종 평가와 최대 세 개의 품질 사유를 전달합니다. */
data class SubmitAiFeedbackRequest(
    @field:NotNull val turnId: UUID,
    @field:NotNull val rating: com.example.mypasture.ai.domain.AiFeedbackRating,
    val reasons: Set<com.example.mypasture.ai.domain.AiFeedbackReason> = emptySet(),
    @field:Size(max = 300) val comment: String? = null,
)

/** 원래 질문의 HMAC을 검증한 뒤 개선 답변을 생성하는 요청입니다. */
data class RegenerateAiAnswerRequest(
    @field:Size(min = 1, max = 300) val question: String,
)

/** 저장된 피드백 상태만 반환하고 민감할 수 있는 자유 의견은 반환하지 않습니다. */
data class AiFeedbackResponse(
    val turnId: UUID,
    val rating: String,
    val reasons: Set<String>,
    val regenerationStatus: String,
    val regeneratedTurnId: UUID?,
    val updatedAt: java.time.Instant,
)

/** 앱이 타입별 UI로 렌더링하는 AI 응답 block의 공통 contract입니다. */
sealed interface AiResponseBlockResponse { val type: String }

data class AiAnswerBlockResponse(
    val text: String,
    override val type: String = "ANSWER",
) : AiResponseBlockResponse

data class AiScriptureBlockResponse(
    val verseId: Long,
    val version: String,
    val bookCode: String,
    val reference: String,
    val text: String,
    val action: String = "OPEN_BIBLE",
    override val type: String = "SCRIPTURE",
) : AiResponseBlockResponse

data class AiPrayerBlockResponse(
    val title: String,
    val content: String,
    override val type: String = "PRAYER",
) : AiResponseBlockResponse

data class AiFollowUpBlockResponse(
    val question: String,
    val options: List<String>,
    val allowSkip: Boolean,
    val skipLabel: String = "새 대화 시작하기",
    override val type: String = "FOLLOW_UP_QUESTION",
) : AiResponseBlockResponse

/** 일반 AI 응답의 본문, 근거, 토큰과 비용을 함께 반환합니다. */
data class AskAiResponse(
    val answer: String,
    val citations: List<BibleCitation>,
    val promptTokens: Int,
    val completionTokens: Int,
    val embeddingTokens: Int,
    val estimatedCostUsd: BigDecimal,
    val embeddingCostUsd: BigDecimal,
    val retrievalStrategy: String,
    val questionIntent: String,
    val cacheHit: Boolean,
    val schemaVersion: String,
    val conversationId: UUID?,
    val turnId: UUID,
    val status: String,
    val interactionMode: String,
    val blocks: List<AiResponseBlockResponse>,
    val nextAction: String,
)

/** streaming 완료 시 클라이언트가 사용량과 검색 방식을 확인하는 SSE payload입니다. */
data class AiStreamUsageResponse(
    val promptTokens: Int,
    val completionTokens: Int,
    val embeddingTokens: Int,
    val estimatedCostUsd: BigDecimal,
    val embeddingCostUsd: BigDecimal,
    val retrievalStrategy: String,
    val questionIntent: String,
    val cacheHit: Boolean,
)

/** 모바일이 하드코딩 없이 표시하는 인증 사용자의 오늘 AI 사용량입니다. */
data class AiDailyUsageResponse(
    val limit: Long?,
    val used: Long,
    val remaining: Long?,
    val unlimited: Boolean,
    val effectiveTier: String,
    val entitlementExpiresAt: java.time.Instant?,
)

/** JWT 사용자의 신앙 질문을 RAG 기반 AI 목자에게 전달합니다. */
@RestController
@RequestMapping("/api/v1/ai")
class AiController(
    private val askBibleAssistant: AskBibleAssistantUseCase,
    private val streamingRunner: AiStreamingCoroutineRunner,
    private val properties: AiProperties,
    private val policyConsentService: PolicyConsentService,
    private val feedbackService: AiAnswerFeedbackService,
) : AiApi {
    override fun todayUsage(principal: JwtPrincipal): AiDailyUsageResponse =
        askBibleAssistant.dailyUsage(principal.userId, principal.subscriptionTier).let {
            AiDailyUsageResponse(it.limit, it.used, it.remaining, it.unlimited, it.effectiveTier.name, it.entitlementExpiresAt)
        }

    override suspend fun chat(principal: JwtPrincipal, request: AskAiRequest): AskAiResponse {
        policyConsentService.requireCurrent(principal.userId, setOf(PolicyScope.ACCOUNT, PolicyScope.AI))
        return askBibleAssistant.askAsync(
            principal.userId,
            principal.subscriptionTier,
            request.question,
            request.conversationId,
        ).toResponse()
    }

    /** 평가가 켜진 경우 검증된 최종 답변만 delta로 보내고 완료 후 인용과 사용량을 전달합니다. */
    override fun stream(principal: JwtPrincipal, request: AskAiRequest): SseEmitter {
        policyConsentService.requireCurrent(principal.userId, setOf(PolicyScope.ACCOUNT, PolicyScope.AI))
        val emitter = SseEmitter(properties.streamTimeoutMs)
        val active = AtomicBoolean(true)
        emitter.onCompletion { active.set(false) }
        emitter.onTimeout { active.set(false) }
        emitter.onError { active.set(false) }

        val accepted = streamingRunner.tryLaunch {
            try {
                val result = askBibleAssistant.streamAsync(
                        userId = principal.userId,
                        subscriptionTier = principal.subscriptionTier,
                        question = request.question,
                        conversationId = request.conversationId,
                        onStage = { stage ->
                            if (active.get()) emitter.send(SseEmitter.event().name("status").data(mapOf("stage" to stage.name)))
                        },
                        onDelta = { delta ->
                            if (!active.get()) throw AiProviderException("AI streaming 연결이 종료되었습니다.")
                            emitter.send(SseEmitter.event().name("delta").data(delta))
                        },
                    )
                if (active.get()) {
                    emitter.send(SseEmitter.event().name("response").data(result.toResponse()))
                    emitter.send(SseEmitter.event().name("citations").data(result.citations))
                    emitter.send(SseEmitter.event().name("usage").data(result.toStreamUsage()))
                    emitter.send(SseEmitter.event().name("done").data(mapOf("status" to "completed")))
                    emitter.complete()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val response = error.toStreamErrorResponse()
                // 상담 질문이나 provider 응답은 남기지 않고 운영에 필요한 분류 정보만 기록합니다.
                logger.warn(
                    "AI stream failed userId={} code={} exception={}",
                    principal.userId,
                    response.code,
                    error::class.qualifiedName,
                )
                if (active.compareAndSet(true, false)) {
                    runCatching {
                        emitter.send(
                            SseEmitter.event().name("error").data(response),
                        )
                    }
                    emitter.complete()
                }
            }
        }
        if (!accepted) {
            active.set(false)
            emitter.completeWithError(AiProviderException("AI streaming 처리 용량을 초과했습니다."))
        }
        return emitter
    }

    override fun feedback(principal: JwtPrincipal, request: SubmitAiFeedbackRequest): AiFeedbackResponse {
        policyConsentService.requireCurrent(principal.userId, setOf(PolicyScope.ACCOUNT, PolicyScope.AI))
        return feedbackService.submit(
            principal.userId,
            request.turnId,
            request.rating,
            request.reasons,
            request.comment,
        ).toResponse()
    }

    override suspend fun regenerate(
        principal: JwtPrincipal,
        turnId: UUID,
        request: RegenerateAiAnswerRequest,
    ): AskAiResponse {
        policyConsentService.requireCurrent(principal.userId, setOf(PolicyScope.ACCOUNT, PolicyScope.AI))
        return feedbackService.regenerate(
            principal.userId,
            principal.subscriptionTier,
            turnId,
            request.question,
        ).toResponse()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(AiController::class.java)
    }
}

private fun com.example.mypasture.ai.domain.AiAnswerFeedback.toResponse() = AiFeedbackResponse(
    turnId = turnId,
    rating = rating.name,
    reasons = reasons.map { it.name }.toSet(),
    regenerationStatus = regenerationStatus.name,
    regeneratedTurnId = regeneratedTurnId,
    updatedAt = updatedAt,
)

/** SSE는 이미 200 응답을 시작했으므로 도메인 예외를 안정적인 event code로 변환합니다. */
private fun Throwable.toStreamErrorResponse(): ApiErrorResponse =
    when (this) {
        is AiInputTooLongException -> ApiErrorResponse.of(AiErrorCode.INPUT_TOO_LONG, message = message ?: AiErrorCode.INPUT_TOO_LONG.defaultMessage)
        is AiQuotaExceededException -> ApiErrorResponse.of(AiErrorCode.QUOTA_EXCEEDED, message = message ?: AiErrorCode.QUOTA_EXCEEDED.defaultMessage)
        is AiGroundingNotFoundException -> ApiErrorResponse.of(AiErrorCode.GROUNDING_NOT_FOUND, message = message ?: AiErrorCode.GROUNDING_NOT_FOUND.defaultMessage)
        is AiAnswerRejectedException -> ApiErrorResponse.of(AiErrorCode.EVALUATION_REJECTED, message = message ?: AiErrorCode.EVALUATION_REJECTED.defaultMessage)
        is AiSafetyException -> ApiErrorResponse.of(AiErrorCode.SAFETY_ESCALATION, message = message ?: AiErrorCode.SAFETY_ESCALATION.defaultMessage)
        is AiProviderException -> ApiErrorResponse.of(AiErrorCode.PROVIDER_UNAVAILABLE, message = message ?: AiErrorCode.PROVIDER_UNAVAILABLE.defaultMessage)
        is AiConversationUnavailableException -> ApiErrorResponse.of(AiErrorCode.CONVERSATION_UNAVAILABLE, message = message ?: AiErrorCode.CONVERSATION_UNAVAILABLE.defaultMessage)
        else -> ApiErrorResponse.of(AiErrorCode.STREAM_FAILED)
    }

private fun GroundedAnswer.toResponse(): AskAiResponse {
    val responseBlocks = buildList<AiResponseBlockResponse> {
        add(AiAnswerBlockResponse(answer))
        citations.forEach { citation ->
            add(
                AiScriptureBlockResponse(
                    verseId = citation.verseId,
                    version = citation.version,
                    bookCode = citation.bookCode,
                    reference = "${citation.book} ${citation.chapter}:${citation.verse}",
                    text = citation.text,
                ),
            )
        }
        prayer?.let { add(AiPrayerBlockResponse(it.title, it.content)) }
        followUpQuestion?.let { add(AiFollowUpBlockResponse(it.question, it.options, it.allowSkip)) }
    }
    val nextAction = if (followUpQuestion == null) AiAssistantNextAction.COMPLETE else AiAssistantNextAction.WAIT_USER_INPUT
    return AskAiResponse(
        answer = answer,
        citations = citations,
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        embeddingTokens = embeddingTokens,
        estimatedCostUsd = estimatedCostUsd,
        embeddingCostUsd = embeddingCostUsd,
        retrievalStrategy = retrievalStrategy.name,
        questionIntent = questionIntent.name,
        cacheHit = cacheHit,
        schemaVersion = AI_ASSISTANT_RESPONSE_SCHEMA_VERSION,
        conversationId = conversationId,
        turnId = turnId,
        status = "COMPLETED",
        interactionMode = interactionMode.name,
        blocks = responseBlocks,
        nextAction = nextAction.name,
    )
}

private fun GroundedAnswer.toStreamUsage() =
    AiStreamUsageResponse(
        promptTokens,
        completionTokens,
        embeddingTokens,
        estimatedCostUsd,
        embeddingCostUsd,
        retrievalStrategy.name,
        questionIntent.name,
        cacheHit,
    )
