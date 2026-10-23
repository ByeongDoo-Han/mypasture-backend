package com.example.mypasture.prayer.presentation

import com.example.mypasture.common.error.ErrorCode
import com.example.mypasture.common.error.toResponse
import com.example.mypasture.prayer.application.PrayerConflictException
import com.example.mypasture.prayer.application.PrayerLimitException
import com.example.mypasture.prayer.application.PrayerNotFoundException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

/** 기도 편지 도메인 오류를 안정적인 HTTP 코드로 변환합니다. */
@RestControllerAdvice(assignableTypes = [PrayerController::class, PrayerRewardCallbackController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class PrayerExceptionHandler {
    @ExceptionHandler(PrayerNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun notFound(request: HttpServletRequest) = PrayerErrorCode.NOT_FOUND.toResponse(request)

    @ExceptionHandler(PrayerConflictException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun conflict(error: PrayerConflictException, request: HttpServletRequest) =
        PrayerErrorCode.CONFLICT.toResponse(request, error.message ?: PrayerErrorCode.CONFLICT.defaultMessage)

    @ExceptionHandler(PrayerLimitException::class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    fun limit(error: PrayerLimitException, request: HttpServletRequest) =
        PrayerErrorCode.LIMIT.toResponse(request, error.message ?: PrayerErrorCode.LIMIT.defaultMessage)

    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun invalid(error: IllegalArgumentException, request: HttpServletRequest) =
        PrayerErrorCode.INVALID.toResponse(request, error.message ?: PrayerErrorCode.INVALID.defaultMessage)
}

/** 기도 편지 API의 공개 오류 코드입니다. */
enum class PrayerErrorCode(override val code: String, override val defaultMessage: String) : ErrorCode {
    NOT_FOUND("PRAYER_NOT_FOUND", "기도 자료를 찾을 수 없습니다."),
    CONFLICT("PRAYER_CONFLICT", "현재 상태에서 요청을 처리할 수 없습니다."),
    LIMIT("PRAYER_LIMIT", "기도 편지 발송 한도에 도달했습니다."),
    INVALID("INVALID_PRAYER_REQUEST", "기도 요청 값이 올바르지 않습니다."),
}
