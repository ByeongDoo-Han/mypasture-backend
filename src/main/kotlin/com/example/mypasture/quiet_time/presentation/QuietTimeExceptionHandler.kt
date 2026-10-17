package com.example.mypasture.quiet_time.presentation

import com.example.mypasture.quiet_time.application.QuietTimeForbiddenException
import com.example.mypasture.quiet_time.application.QuietTimeAdminOperationConflictException
import com.example.mypasture.quiet_time.application.QuietTimeNotFoundException
import com.example.mypasture.quiet_time.application.QuietTimeParticipationRequiredException
import com.example.mypasture.quiet_time.application.QuietTimePassageLockedException
import com.example.mypasture.quiet_time.application.QuietTimeQuestionNotReadyException
import com.example.mypasture.quiet_time.infrastructure.QuietTimeGenerationLimitException
import com.example.mypasture.quiet_time.application.QuietTimeQrExpiredException
import com.example.mypasture.quiet_time.application.QuietTimeQrNotOpenException
import com.example.mypasture.quiet_time.application.QuietTimeQrRevokedException
import com.example.mypasture.quiet_time.application.QuietTimeSessionClosedException
import com.example.mypasture.moderation.application.UserGeneratedContentRejectedException
import com.example.mypasture.common.error.ErrorCode
import com.example.mypasture.common.error.toResponse
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

/** 큐티목장과 QR 정책 예외를 일관된 HTTP 응답으로 변환하는 controller advice입니다. */
@RestControllerAdvice(assignableTypes = [QuietTimeController::class, QuietTimeAdminController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class QuietTimeExceptionHandler {
    @ExceptionHandler(QuietTimeGenerationLimitException::class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    fun handleGenerationLimit(request: HttpServletRequest) =
        QuietTimeErrorCode.GENERATION_LIMIT.toResponse(request)
    @ExceptionHandler(QuietTimeQuestionNotReadyException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleQuestionNotReady(request: HttpServletRequest) =
        QuietTimeErrorCode.QUESTION_NOT_READY.toResponse(request)
    @ExceptionHandler(QuietTimeAdminOperationConflictException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleAdminOperationConflict(request: HttpServletRequest) =
        QuietTimeErrorCode.ADMIN_OPERATION_CONFLICT.toResponse(request)

    @ExceptionHandler(QuietTimeNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleNotFound(exception: QuietTimeNotFoundException, request: HttpServletRequest) =
        QuietTimeErrorCode.NOT_FOUND.toResponse(request, exception.message ?: QuietTimeErrorCode.NOT_FOUND.defaultMessage)

    @ExceptionHandler(QuietTimeForbiddenException::class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    fun handleForbidden(request: HttpServletRequest) =
        QuietTimeErrorCode.LEADER_REQUIRED.toResponse(request)

    @ExceptionHandler(QuietTimeParticipationRequiredException::class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    fun handleParticipationRequired(request: HttpServletRequest) =
        QuietTimeErrorCode.PARTICIPATION_REQUIRED.toResponse(request)

    @ExceptionHandler(QuietTimeQrNotOpenException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleQrNotOpen(request: HttpServletRequest) =
        QuietTimeErrorCode.QR_NOT_OPEN.toResponse(request)

    @ExceptionHandler(QuietTimeQrExpiredException::class)
    @ResponseStatus(HttpStatus.GONE)
    fun handleQrExpired(request: HttpServletRequest) =
        QuietTimeErrorCode.QR_EXPIRED.toResponse(request)

    @ExceptionHandler(QuietTimeQrRevokedException::class)
    @ResponseStatus(HttpStatus.GONE)
    fun handleQrRevoked(request: HttpServletRequest) =
        QuietTimeErrorCode.QR_REVOKED.toResponse(request)

    @ExceptionHandler(QuietTimeSessionClosedException::class)
    @ResponseStatus(HttpStatus.GONE)
    fun handleSessionClosed(request: HttpServletRequest) =
        QuietTimeErrorCode.SESSION_CLOSED.toResponse(request)

    @ExceptionHandler(QuietTimePassageLockedException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handlePassageLocked(request: HttpServletRequest) =
        QuietTimeErrorCode.PASSAGE_LOCKED.toResponse(request)

    @ExceptionHandler(UserGeneratedContentRejectedException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleRejectedContent(exception: UserGeneratedContentRejectedException, request: HttpServletRequest) =
        QuietTimeErrorCode.CONTENT_REJECTED.toResponse(request, exception.message ?: QuietTimeErrorCode.CONTENT_REJECTED.defaultMessage)

    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleBadRequest(exception: IllegalArgumentException, request: HttpServletRequest) =
        QuietTimeErrorCode.INVALID_REQUEST.toResponse(request, exception.message ?: QuietTimeErrorCode.INVALID_REQUEST.defaultMessage)
}

/** 큐티 도메인이 QR 정책과 권한 실패를 표현하는 안정적인 오류 코드입니다. */
enum class QuietTimeErrorCode(
    override val code: String,
    override val defaultMessage: String,
) : ErrorCode {
    NOT_FOUND("QUIET_TIME_NOT_FOUND", "큐티목장 리소스를 찾을 수 없습니다."),
    LEADER_REQUIRED("QUIET_TIME_LEADER_REQUIRED", "목장 리더만 QR을 관리할 수 있습니다."),
    PARTICIPATION_REQUIRED("QUIET_TIME_PARTICIPATION_REQUIRED", "이 QT 모임에 참여한 목원만 답변을 볼 수 있습니다."),
    QR_NOT_OPEN("QUIET_TIME_QR_NOT_OPEN", "아직 참여 가능한 시간이 아닙니다."),
    QR_EXPIRED("QUIET_TIME_QR_EXPIRED", "참여 가능한 시간이 종료되었습니다."),
    QR_REVOKED("QUIET_TIME_QR_REVOKED", "폐기되었거나 새 QR로 교체된 코드입니다."),
    SESSION_CLOSED("QUIET_TIME_SESSION_CLOSED", "종료된 큐티 모임입니다."),
    PASSAGE_LOCKED("QUIET_TIME_PASSAGE_LOCKED", "참여·답변·묵상이 시작된 이후에는 주제 말씀을 변경할 수 없습니다."),
    QUESTION_NOT_READY("QUIET_TIME_QUESTION_NOT_READY", "질문 생성이 완료된 뒤 모임을 만들어 주세요."),
    GENERATION_LIMIT("QUIET_TIME_GENERATION_LIMIT", "오늘 생성할 수 있는 새 QT 질문은 5개까지입니다."),
    ADMIN_OPERATION_CONFLICT("QUIET_TIME_ADMIN_OPERATION_CONFLICT", "이미 다른 관리자 작업에 사용된 operationId입니다."),
    CONTENT_REJECTED("QUIET_TIME_CONTENT_REJECTED", "공동체 운영 정책에 따라 게시할 수 없는 표현이 포함되어 있습니다."),
    INVALID_REQUEST("INVALID_QUIET_TIME_REQUEST", "큐티목장 요청이 올바르지 않습니다."),
}
