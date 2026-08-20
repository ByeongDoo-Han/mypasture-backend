package com.example.mypasture.faith_profile.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.faith_profile.application.FaithProfileService
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** 모바일 클라이언트의 신앙 성향 테스트 HTTP 요청을 받는 controller입니다. */
@RestController
@RequestMapping("/api/v1/faith-profile")
class FaithProfileController(
    private val faithProfileService: FaithProfileService,
) : FaithProfileApi {
    override fun currentTest(): FaithTestResponse =
        faithProfileService.getCurrentTest().toResponse()

    override fun startAssessment(principal: JwtPrincipal, request: StartFaithAssessmentRequest): FaithAssessmentResponse =
        faithProfileService.startAssessment(principal.userId).toResponse()

    override fun submitAnswer(
        principal: JwtPrincipal,
        assessmentId: UUID,
        request: SubmitFaithAnswerRequest,
    ): FaithAnswerResponse =
        faithProfileService.answerQuestion(principal.userId, assessmentId, request.questionId, request.score).toResponse()

    override fun completeAssessment(principal: JwtPrincipal, assessmentId: UUID): FaithResultResponse =
        faithProfileService.completeAssessment(principal.userId, assessmentId).toResponse()

    override fun result(principal: JwtPrincipal, resultId: UUID): FaithResultResponse =
        faithProfileService.getResult(principal.userId, resultId).toResponse()
}
