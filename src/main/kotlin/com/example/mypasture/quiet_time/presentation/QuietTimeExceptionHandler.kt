package com.example.mypasture.quiet_time.presentation

import com.example.mypasture.quiet_time.application.QuietTimeForbiddenException
import com.example.mypasture.quiet_time.application.QuietTimeNotFoundException
import com.example.mypasture.quiet_time.application.QuietTimeQrExpiredException
import com.example.mypasture.quiet_time.application.QuietTimeQrNotOpenException
import com.example.mypasture.quiet_time.application.QuietTimeQrRevokedException
import com.example.mypasture.quiet_time.application.QuietTimeSessionClosedException
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

/** 큐티목장과 QR 정책 예외를 일관된 HTTP 응답으로 변환하는 controller advice입니다. */
@RestControllerAdvice(assignableTypes = [QuietTimeController::class])
class QuietTimeExceptionHandler {
    @ExceptionHandler(QuietTimeNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleNotFound(exception: QuietTimeNotFoundException) =
        QuietTimeErrorResponse("QUIET_TIME_NOT_FOUND", exception.message ?: "큐티목장 리소스를 찾을 수 없습니다.")

    @ExceptionHandler(QuietTimeForbiddenException::class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    fun handleForbidden() =
        QuietTimeErrorResponse("QUIET_TIME_LEADER_REQUIRED", "목장 리더만 QR을 관리할 수 있습니다.")

    @ExceptionHandler(QuietTimeQrNotOpenException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleQrNotOpen() =
        QuietTimeErrorResponse("QUIET_TIME_QR_NOT_OPEN", "아직 참여 가능한 시간이 아닙니다.")

    @ExceptionHandler(QuietTimeQrExpiredException::class)
    @ResponseStatus(HttpStatus.GONE)
    fun handleQrExpired() =
        QuietTimeErrorResponse("QUIET_TIME_QR_EXPIRED", "참여 가능한 시간이 종료되었습니다.")

    @ExceptionHandler(QuietTimeQrRevokedException::class)
    @ResponseStatus(HttpStatus.GONE)
    fun handleQrRevoked() =
        QuietTimeErrorResponse("QUIET_TIME_QR_REVOKED", "폐기되었거나 새 QR로 교체된 코드입니다.")

    @ExceptionHandler(QuietTimeSessionClosedException::class)
    @ResponseStatus(HttpStatus.GONE)
    fun handleSessionClosed() =
        QuietTimeErrorResponse("QUIET_TIME_SESSION_CLOSED", "종료된 큐티 모임입니다.")

    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleBadRequest(exception: IllegalArgumentException) =
        QuietTimeErrorResponse("INVALID_QUIET_TIME_REQUEST", exception.message ?: "큐티목장 요청이 올바르지 않습니다.")
}
