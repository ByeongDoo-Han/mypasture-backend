package com.example.mypasture.ai.presentation

import com.example.mypasture.ai.application.AiProviderException
import com.example.mypasture.ai.application.AskBibleAssistantUseCase
import com.example.mypasture.ai.domain.BibleCitation
import com.example.mypasture.ai.domain.GroundedAnswer
import com.example.mypasture.auth.infrastructure.JwtPrincipal
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
    val cacheHit: Boolean,
)

/** JWT 사용자의 신앙 질문을 RAG 기반 AI 목자에게 전달합니다. */
@RestController
@RequestMapping("/api/v1/ai")
class AiController(
    private val askBibleAssistant: AskBibleAssistantUseCase,
    @Qualifier("aiStreamingExecutor") private val streamingExecutor: Executor,
    private val properties: AiProperties,
) : AiApi {
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
                    val result = askBibleAssistant.stream(principal.userId, principal.subscriptionTier, request.question) { delta ->
                        if (!active.get()) throw AiProviderException("AI streaming 연결이 종료되었습니다.")
                        emitter.send(SseEmitter.event().name("delta").data(delta))
                    }
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
                                    AiErrorResponse("AI_STREAM_FAILED", error.message ?: "AI streaming 요청에 실패했습니다."),
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
        cacheHit,
    )
