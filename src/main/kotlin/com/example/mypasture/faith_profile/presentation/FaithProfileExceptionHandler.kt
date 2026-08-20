package com.example.mypasture.faith_profile.presentation

import com.example.mypasture.faith_profile.application.FaithAnswerAlreadyExistsException
import com.example.mypasture.faith_profile.application.FaithAssessmentAlreadyCompletedException
import com.example.mypasture.faith_profile.application.FaithProfileBadRequestException
import com.example.mypasture.faith_profile.application.FaithProfileForbiddenException
import com.example.mypasture.faith_profile.application.FaithProfileNotFoundException
import com.example.mypasture.common.error.ErrorCode
import com.example.mypasture.common.error.toResponse
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

/** 신앙 성향 테스트 도메인 예외를 HTTP 응답으로 변환하는 controller advice입니다. */
@RestControllerAdvice(assignableTypes = [FaithProfileController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class FaithProfileExceptionHandler {
    @ExceptionHandler(FaithProfileForbiddenException::class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    fun handleForbidden(request: HttpServletRequest) =
        FaithProfileErrorCode.FORBIDDEN.toResponse(request)

    @ExceptionHandler(FaithProfileNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleNotFound(exception: FaithProfileNotFoundException, request: HttpServletRequest) =
        FaithProfileErrorCode.NOT_FOUND.toResponse(request, exception.message ?: FaithProfileErrorCode.NOT_FOUND.defaultMessage)

    @ExceptionHandler(FaithProfileBadRequestException::class, IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleBadRequest(exception: RuntimeException, request: HttpServletRequest) =
        FaithProfileErrorCode.INVALID_REQUEST.toResponse(request, exception.message ?: FaithProfileErrorCode.INVALID_REQUEST.defaultMessage)

    @ExceptionHandler(FaithAnswerAlreadyExistsException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleDuplicateAnswer(request: HttpServletRequest) =
        FaithProfileErrorCode.ANSWER_ALREADY_EXISTS.toResponse(request)

    @ExceptionHandler(FaithAssessmentAlreadyCompletedException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleCompletedAssessment(request: HttpServletRequest) =
        FaithProfileErrorCode.ASSESSMENT_ALREADY_COMPLETED.toResponse(request)
}

/** 신앙 성향 도메인이 권한과 테스트 상태 실패를 표현하는 안정적인 오류 코드입니다. */
enum class FaithProfileErrorCode(
    override val code: String,
    override val defaultMessage: String,
) : ErrorCode {
    FORBIDDEN("FAITH_PROFILE_FORBIDDEN", "현재 사용자의 신앙 성향 테스트가 아닙니다."),
    NOT_FOUND("FAITH_PROFILE_NOT_FOUND", "신앙 성향 테스트 리소스를 찾을 수 없습니다."),
    INVALID_REQUEST("INVALID_FAITH_PROFILE_REQUEST", "신앙 성향 테스트 요청이 올바르지 않습니다."),
    ANSWER_ALREADY_EXISTS("FAITH_ANSWER_ALREADY_EXISTS", "이미 답변한 문항입니다."),
    ASSESSMENT_ALREADY_COMPLETED("FAITH_ASSESSMENT_ALREADY_COMPLETED", "이미 완료된 신앙 성향 테스트입니다."),
}
