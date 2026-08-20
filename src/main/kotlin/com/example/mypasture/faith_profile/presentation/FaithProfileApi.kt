package com.example.mypasture.faith_profile.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import java.util.UUID

/** Swagger/OpenAPI 문서화를 controller 구현과 분리하기 위한 신앙 성향 테스트 API contract입니다. */
interface FaithProfileApi {
    @GetMapping("/tests/current")
    fun currentTest(): FaithTestResponse

    @PostMapping("/assessments")
    fun startAssessment(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Valid @RequestBody request: StartFaithAssessmentRequest,
    ): FaithAssessmentResponse

    @PostMapping("/assessments/{assessmentId}/answers")
    fun submitAnswer(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @PathVariable assessmentId: UUID,
        @Valid @RequestBody request: SubmitFaithAnswerRequest,
    ): FaithAnswerResponse

    @PostMapping("/assessments/{assessmentId}/complete")
    fun completeAssessment(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @PathVariable assessmentId: UUID,
    ): FaithResultResponse

    @GetMapping("/results/{resultId}")
    fun result(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @PathVariable resultId: UUID,
    ): FaithResultResponse
}
