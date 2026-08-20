package com.example.mypasture.quiet_time.presentation

import com.example.mypasture.quiet_time.application.QuietTimeForbiddenException
import com.example.mypasture.quiet_time.application.QuietTimeNotFoundException
import com.example.mypasture.quiet_time.application.QuietTimeQrExpiredException
import com.example.mypasture.quiet_time.application.QuietTimeQrNotOpenException
import com.example.mypasture.quiet_time.application.QuietTimeQrRevokedException
import com.example.mypasture.quiet_time.application.QuietTimeSessionClosedException
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
@RestControllerAdvice(assignableTypes = [QuietTimeController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class QuietTimeExceptionHandler {
    @ExceptionHandler(QuietTimeNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleNotFound(exception: QuietTimeNotFoundException, request: HttpServletRequest) =
        QuietTimeErrorCode.NOT_FOUND.toResponse(request, exception.message ?: QuietTimeErrorCode.NOT_FOUND.defaultMessage)

    @ExceptionHandler(QuietTimeForbiddenException::class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    fun handleForbidden(request: HttpServletRequest) =
        QuietTimeErrorCode.LEADER_REQUIRED.toResponse(request)

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
    QR_NOT_OPEN("QUIET_TIME_QR_NOT_OPEN", "아직 참여 가능한 시간이 아닙니다."),
    QR_EXPIRED("QUIET_TIME_QR_EXPIRED", "참여 가능한 시간이 종료되었습니다."),
    QR_REVOKED("QUIET_TIME_QR_REVOKED", "폐기되었거나 새 QR로 교체된 코드입니다."),
    SESSION_CLOSED("QUIET_TIME_SESSION_CLOSED", "종료된 큐티 모임입니다."),
    INVALID_REQUEST("INVALID_QUIET_TIME_REQUEST", "큐티목장 요청이 올바르지 않습니다."),
}
