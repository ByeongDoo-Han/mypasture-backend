package com.example.mypasture.ai.presentation

import com.example.mypasture.ai.application.AiProviderException
import com.example.mypasture.ai.application.AiGroundingNotFoundException
import com.example.mypasture.ai.application.AiInputTooLongException
import com.example.mypasture.ai.application.AiQuotaExceededException
import com.example.mypasture.ai.application.AiSafetyException
import com.example.mypasture.ai.application.AskBibleAssistantUseCase
import com.example.mypasture.ai.domain.BibleCitation
import com.example.mypasture.ai.domain.GroundedAnswer
import com.example.mypasture.ai.domain.AiAssistantStage
import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.common.error.ApiErrorResponse
import com.example.mypasture.config.AiProperties
import jakarta.validation.constraints.Size
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.math.BigDecimal
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** AI 목자에게 전달할 최대 300자의 사용자 질문입니다. */
data class AskAiRequest(@field:Size(min = 1, max = 300) val question: String)

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
)

/** JWT 사용자의 신앙 질문을 RAG 기반 AI 목자에게 전달합니다. */
@RestController
@RequestMapping("/api/v1/ai")
class AiController(
    private val askBibleAssistant: AskBibleAssistantUseCase,
    @Qualifier("aiStreamingExecutor") private val streamingExecutor: Executor,
    private val properties: AiProperties,
) : AiApi {
    override fun todayUsage(principal: JwtPrincipal): AiDailyUsageResponse =
        askBibleAssistant.dailyUsage(principal.userId, principal.subscriptionTier).let {
            AiDailyUsageResponse(it.limit, it.used, it.remaining, it.unlimited)
        }

    override fun chat(principal: JwtPrincipal, request: AskAiRequest): AskAiResponse =
        askBibleAssistant.ask(principal.userId, principal.subscriptionTier, request.question).toResponse()

    /** OpenAI delta를 기다리지 않고 전달하며 완료 후 검증된 인용과 사용량을 별도 이벤트로 보냅니다. */
    override fun stream(principal: JwtPrincipal, request: AskAiRequest): SseEmitter {
        val emitter = SseEmitter(properties.streamTimeoutMs)
        val active = AtomicBoolean(true)
        emitter.onCompletion { active.set(false) }
        emitter.onTimeout { active.set(false) }
        emitter.onError { active.set(false) }

        try {
            streamingExecutor.execute {
                runCatching {
                    val result = askBibleAssistant.stream(
                        userId = principal.userId,
                        subscriptionTier = principal.subscriptionTier,
                        question = request.question,
                        onStage = { stage ->
                            if (active.get()) emitter.send(SseEmitter.event().name("status").data(mapOf("stage" to stage.name)))
                        },
                        onDelta = { delta ->
                            if (!active.get()) throw AiProviderException("AI streaming 연결이 종료되었습니다.")
                            emitter.send(SseEmitter.event().name("delta").data(delta))
                        },
                    )
                    if (active.get()) {
                        emitter.send(SseEmitter.event().name("citations").data(result.citations))
                        emitter.send(SseEmitter.event().name("usage").data(result.toStreamUsage()))
                        emitter.send(SseEmitter.event().name("done").data(mapOf("status" to "completed")))
                        emitter.complete()
                    }
                }.onFailure { error ->
                    if (active.compareAndSet(true, false)) {
                        runCatching {
                            emitter.send(
                                SseEmitter.event().name("error").data(
                                    error.toStreamErrorResponse(),
                                ),
                            )
                        }
                        emitter.complete()
                    }
                }
            }
        } catch (error: RuntimeException) {
            active.set(false)
            emitter.completeWithError(AiProviderException("AI streaming 처리 용량을 초과했습니다.", error))
        }
        return emitter
    }
}

/** SSE는 이미 200 응답을 시작했으므로 도메인 예외를 안정적인 event code로 변환합니다. */
private fun Throwable.toStreamErrorResponse(): ApiErrorResponse =
    when (this) {
        is AiInputTooLongException -> ApiErrorResponse.of(AiErrorCode.INPUT_TOO_LONG, message = message ?: AiErrorCode.INPUT_TOO_LONG.defaultMessage)
        is AiQuotaExceededException -> ApiErrorResponse.of(AiErrorCode.QUOTA_EXCEEDED, message = message ?: AiErrorCode.QUOTA_EXCEEDED.defaultMessage)
        is AiGroundingNotFoundException -> ApiErrorResponse.of(AiErrorCode.GROUNDING_NOT_FOUND, message = message ?: AiErrorCode.GROUNDING_NOT_FOUND.defaultMessage)
        is AiSafetyException -> ApiErrorResponse.of(AiErrorCode.SAFETY_ESCALATION, message = message ?: AiErrorCode.SAFETY_ESCALATION.defaultMessage)
        is AiProviderException -> ApiErrorResponse.of(AiErrorCode.PROVIDER_UNAVAILABLE, message = message ?: AiErrorCode.PROVIDER_UNAVAILABLE.defaultMessage)
        else -> ApiErrorResponse.of(AiErrorCode.STREAM_FAILED)
    }

private fun GroundedAnswer.toResponse() =
    AskAiResponse(
        answer,
        citations,
        promptTokens,
        completionTokens,
        embeddingTokens,
        estimatedCostUsd,
        embeddingCostUsd,
        retrievalStrategy.name,
        questionIntent.name,
        cacheHit,
    )

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
