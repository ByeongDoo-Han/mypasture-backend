package com.example.mypasture.faith_profile.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import java.util.UUID

/** Swagger/OpenAPI 문서화를 controller 구현과 분리하기 위한 신앙 성향 테스트 API contract입니다. */
@Tag(name = "신앙 성향", description = "신앙 성향 검사와 본인 결과를 관리합니다.")
interface FaithProfileApi {
    @Operation(summary = "현재 신앙 성향 검사 조회", description = "현재 응답할 수 있는 신앙 성향 검사의 질문과 구성을 반환합니다.")
    @GetMapping("/tests/current")
    fun currentTest(): FaithTestResponse

    @Operation(summary = "신앙 성향 검사 시작", description = "로그인한 사용자의 검사 응답 세션을 생성합니다.")
    @PostMapping("/assessments")
    fun startAssessment(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Valid @RequestBody request: StartFaithAssessmentRequest,
    ): FaithAssessmentResponse

    @Operation(summary = "신앙 성향 답변 제출", description = "본인 검사 세션의 지정 질문에 응답을 저장합니다.")
    @PostMapping("/assessments/{assessmentId}/answers")
    fun submitAnswer(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Parameter(description = "본인이 진행 중인 신앙 성향 검사 UUID.") @PathVariable assessmentId: UUID,
        @Valid @RequestBody request: SubmitFaithAnswerRequest,
    ): FaithAnswerResponse

    @Operation(summary = "신앙 성향 검사 완료", description = "본인 검사 세션의 응답을 확정하고 결과를 생성합니다.")
    @PostMapping("/assessments/{assessmentId}/complete")
    fun completeAssessment(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Parameter(description = "본인이 진행 중인 신앙 성향 검사 UUID.") @PathVariable assessmentId: UUID,
    ): FaithResultResponse

    @Operation(summary = "내 신앙 성향 결과 조회", description = "본인에게 허용된 검사 결과만 반환합니다.")
    @GetMapping("/results/{resultId}")
    fun result(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Parameter(description = "조회할 신앙 성향 결과 UUID.") @PathVariable resultId: UUID,
    ): FaithResultResponse
}
