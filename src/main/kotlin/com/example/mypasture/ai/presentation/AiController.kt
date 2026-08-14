package com.example.mypasture.ai.presentation

import com.example.mypasture.ai.application.AskBibleAssistantUseCase
import com.example.mypasture.ai.domain.BibleCitation
import com.example.mypasture.auth.infrastructure.JwtPrincipal
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.math.BigDecimal
import java.util.concurrent.CompletableFuture

data class AskAiRequest(@field:Size(min = 1, max = 300) val question: String)
data class AskAiResponse(val answer: String, val citations: List<BibleCitation>, val promptTokens: Int, val completionTokens: Int, val estimatedCostUsd: BigDecimal, val cacheHit: Boolean)

/** JWT 사용자의 신앙 질문을 RAG 기반 AI 목자에게 전달합니다. */
@RestController @RequestMapping("/api/v1/ai")
class AiController(private val askBibleAssistant: AskBibleAssistantUseCase) {
    @PostMapping("/chat")
    fun chat(@AuthenticationPrincipal principal: JwtPrincipal, @Valid @RequestBody request: AskAiRequest): AskAiResponse = askBibleAssistant.ask(principal.userId, principal.subscriptionTier, request.question).toResponse()

    /** SSE를 지원하지 않는 환경은 일반 chat API를 사용하고, 지원 환경은 답변을 문장 단위로 받을 수 있습니다. */
    @PostMapping("/chat/stream", produces = ["text/event-stream"])
    fun stream(@AuthenticationPrincipal principal: JwtPrincipal, @Valid @RequestBody request: AskAiRequest): SseEmitter {
        val emitter = SseEmitter(30_000)
        CompletableFuture.runAsync {
            runCatching { askBibleAssistant.ask(principal.userId, principal.subscriptionTier, request.question) }
                .onSuccess { result ->
                    result.answer.split(Regex("(?<=[.!?。]|다\\.)\\s+")).filter { it.isNotBlank() }.forEach { emitter.send(SseEmitter.event().name("delta").data(it)) }
                    emitter.send(SseEmitter.event().name("citations").data(result.citations)); emitter.complete()
                }.onFailure(emitter::completeWithError)
        }
        return emitter
    }
}
private fun com.example.mypasture.ai.domain.GroundedAnswer.toResponse() = AskAiResponse(answer, citations, promptTokens, completionTokens, estimatedCostUsd, cacheHit)
