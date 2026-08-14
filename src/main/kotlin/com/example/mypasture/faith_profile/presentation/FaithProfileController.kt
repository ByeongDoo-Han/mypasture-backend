package com.example.mypasture.faith_profile.presentation

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

    override fun startAssessment(request: StartFaithAssessmentRequest): FaithAssessmentResponse =
        faithProfileService.startAssessment(request.userId).toResponse()

    override fun submitAnswer(
        assessmentId: UUID,
        request: SubmitFaithAnswerRequest,
    ): FaithAnswerResponse =
        faithProfileService.answerQuestion(assessmentId, request.questionId, request.score).toResponse()

    override fun completeAssessment(assessmentId: UUID): FaithResultResponse =
        faithProfileService.completeAssessment(assessmentId).toResponse()

    override fun result(resultId: UUID): FaithResultResponse =
        faithProfileService.getResult(resultId).toResponse()
}
