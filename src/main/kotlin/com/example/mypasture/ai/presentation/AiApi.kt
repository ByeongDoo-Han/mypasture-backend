package com.example.mypasture.ai.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/** Swagger/OpenAPI 문서화를 controller 구현과 분리한 AI 목자 API contract입니다. */
interface AiApi {
    @PostMapping("/chat")
    fun chat(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Valid @RequestBody request: AskAiRequest,
    ): AskAiResponse

    @PostMapping("/chat/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Valid @RequestBody request: AskAiRequest,
    ): SseEmitter
}
