package com.example.mypasture.ai.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import jakarta.validation.Valid
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/** Swagger/OpenAPI 문서화를 controller 구현과 분리한 AI 목자 API contract입니다. */
@Tag(name = "AI 목자", description = "신앙 질문, 답변 생성, 사용량과 피드백을 처리합니다.")
interface AiApi {
    @Operation(summary = "오늘 AI 사용량 조회", description = "로그인한 사용자의 오늘 사용량과 남은 이용 가능 횟수를 반환합니다.")
    @GetMapping("/usage/today")
    fun todayUsage(
        @AuthenticationPrincipal principal: JwtPrincipal,
    ): AiDailyUsageResponse

    /** 무관/모호한 질문은 검색 전에 422 AI_QUESTION_OUT_OF_SCOPE / AI_QUESTION_SCOPE_UNCLEAR로 안내합니다. 구절과 기도문은 반환하지 않습니다. */
    @Operation(summary = "AI 목자에게 질문", description = "신앙 관련 질문에 근거 구절과 답변을 생성합니다. 같은 장의 최대 10절 범위를 명시하면 전체 범위를 근거로 사용합니다. 무관하거나 모호한 질문 및 잘못된 절 범위는 422 오류로 종료합니다.")
    @PostMapping("/chat")
    suspend fun chat(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Valid @RequestBody request: AskAiRequest,
    ): AskAiResponse

    @Operation(summary = "AI 요청 상태 조회", description = "본인의 clientRequestId에 해당하는 생성 요청의 처리 상태를 조회합니다.")
    @GetMapping("/requests/{clientRequestId}")
    fun requestStatus(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Parameter(description = "클라이언트가 지정한 AI 요청 UUID.") @PathVariable clientRequestId: java.util.UUID,
    ): AiRequestStatusResponse

    /** 무관/모호한 질문은 HTTP 200 연결 후 event:error로 동일 범위 코드를 반환하고 종료하며 delta·구절·기도문을 보내지 않습니다. */
    @Operation(summary = "AI 목자 답변 스트리밍", description = "liveDraft=true이면 생성 중 검증 가능한 인용이 포함된 초안을 delta로 전송합니다. 품질 평가에서 재작성하면 draft_reset 뒤 새 초안을 전송하고 최종 response·done으로 확정합니다. 기본값은 기존 앱을 위해 검증된 최종 답변만 전송합니다.")
    @PostMapping("/chat/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Valid @RequestBody request: AskAiRequest,
        @Parameter(description = "생성 중 임시 답변과 draft_reset 이벤트를 지원하는 클라이언트만 true로 설정합니다.") @RequestParam(defaultValue = "false") liveDraft: Boolean,
    ): SseEmitter

    @Operation(summary = "AI 답변 피드백 등록", description = "본인이 받은 AI 답변에 대한 평가와 피드백을 저장합니다.")
    @PostMapping("/feedback")
    fun feedback(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Valid @RequestBody request: SubmitAiFeedbackRequest,
    ): AiFeedbackResponse

    /** 본인 원 질문에도 현재 범위 정책을 적용하며 무관/모호한 질문은 422 범위 안내로 구절·기도문 재생성을 중단합니다. */
    @Operation(summary = "AI 답변 재생성", description = "본인 답변의 원 질문을 현재 범위 정책으로 다시 검사한 뒤 재생성합니다. 범위 밖 또는 모호한 질문은 422로 종료합니다.")
    @PostMapping("/feedback/{turnId}/regenerate")
    suspend fun regenerate(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Parameter(description = "본인 AI 답변 턴의 UUID.") @PathVariable turnId: java.util.UUID,
        @Valid @RequestBody request: RegenerateAiAnswerRequest,
    ): AskAiResponse
}
