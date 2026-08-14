package com.example.mypasture.faith_profile.presentation

import com.example.mypasture.faith_profile.application.FaithAnswerAlreadyExistsException
import com.example.mypasture.faith_profile.application.FaithAssessmentAlreadyCompletedException
import com.example.mypasture.faith_profile.application.FaithProfileBadRequestException
import com.example.mypasture.faith_profile.application.FaithProfileNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

/** 신앙 성향 테스트 도메인 예외를 HTTP 응답으로 변환하는 controller advice입니다. */
@RestControllerAdvice(assignableTypes = [FaithProfileController::class])
class FaithProfileExceptionHandler {
    @ExceptionHandler(FaithProfileNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleNotFound(exception: FaithProfileNotFoundException): FaithProfileErrorResponse =
        FaithProfileErrorResponse("FAITH_PROFILE_NOT_FOUND", exception.message ?: "신앙 성향 테스트 리소스를 찾을 수 없습니다.")

    @ExceptionHandler(FaithProfileBadRequestException::class, IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleBadRequest(exception: RuntimeException): FaithProfileErrorResponse =
        FaithProfileErrorResponse("INVALID_FAITH_PROFILE_REQUEST", exception.message ?: "신앙 성향 테스트 요청이 올바르지 않습니다.")

    @ExceptionHandler(FaithAnswerAlreadyExistsException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleDuplicateAnswer(): FaithProfileErrorResponse =
        FaithProfileErrorResponse("FAITH_ANSWER_ALREADY_EXISTS", "이미 답변한 문항입니다.")

    @ExceptionHandler(FaithAssessmentAlreadyCompletedException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleCompletedAssessment(): FaithProfileErrorResponse =
        FaithProfileErrorResponse("FAITH_ASSESSMENT_ALREADY_COMPLETED", "이미 완료된 신앙 성향 테스트입니다.")
}
