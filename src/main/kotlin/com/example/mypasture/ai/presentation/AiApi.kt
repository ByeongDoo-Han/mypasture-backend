package com.example.mypasture.ai.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/** Swagger/OpenAPI 문서화를 controller 구현과 분리한 AI 목자 API contract입니다. */
interface AiApi {
    @GetMapping("/usage/today")
    fun todayUsage(
        @AuthenticationPrincipal principal: JwtPrincipal,
    ): AiDailyUsageResponse

    @PostMapping("/chat")
    suspend fun chat(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Valid @RequestBody request: AskAiRequest,
    ): AskAiResponse

    @PostMapping("/chat/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Valid @RequestBody request: AskAiRequest,
    ): SseEmitter

    @PostMapping("/feedback")
    fun feedback(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Valid @RequestBody request: SubmitAiFeedbackRequest,
    ): AiFeedbackResponse

    @PostMapping("/feedback/{turnId}/regenerate")
    suspend fun regenerate(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @PathVariable turnId: java.util.UUID,
        @Valid @RequestBody request: RegenerateAiAnswerRequest,
    ): AskAiResponse
}
