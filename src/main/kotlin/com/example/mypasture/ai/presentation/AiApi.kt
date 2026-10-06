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

    /** 무관/모호한 질문은 검색 전에 422 AI_QUESTION_OUT_OF_SCOPE / AI_QUESTION_SCOPE_UNCLEAR로 안내합니다. 구절과 기도문은 반환하지 않습니다. */
    @PostMapping("/chat")
    suspend fun chat(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Valid @RequestBody request: AskAiRequest,
    ): AskAiResponse

    @GetMapping("/requests/{clientRequestId}")
    fun requestStatus(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @PathVariable clientRequestId: java.util.UUID,
    ): AiRequestStatusResponse

    /** 무관/모호한 질문은 HTTP 200 연결 후 event:error로 동일 범위 코드를 반환하고 종료하며 delta·구절·기도문을 보내지 않습니다. */
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

    /** 본인 원 질문에도 현재 범위 정책을 적용하며 무관/모호한 질문은 422 범위 안내로 구절·기도문 재생성을 중단합니다. */
    @PostMapping("/feedback/{turnId}/regenerate")
    suspend fun regenerate(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @PathVariable turnId: java.util.UUID,
        @Valid @RequestBody request: RegenerateAiAnswerRequest,
    ): AskAiResponse
}
