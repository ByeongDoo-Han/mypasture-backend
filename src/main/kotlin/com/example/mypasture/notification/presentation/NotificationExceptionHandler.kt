package com.example.mypasture.notification.presentation

import com.example.mypasture.common.error.ErrorCode
import com.example.mypasture.common.error.toResponse
import com.example.mypasture.notification.application.NotificationInvalidCursorException
import com.example.mypasture.notification.application.NotificationNotFoundException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

/** 알림센터 도메인 오류를 모바일 공통 API 오류 형식으로 변환합니다. */
@RestControllerAdvice(assignableTypes = [NotificationController::class, NotificationAdminController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class NotificationExceptionHandler {
    @ExceptionHandler(NotificationNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun notFound(exception: NotificationNotFoundException, request: HttpServletRequest) =
        NotificationErrorCode.NOT_FOUND.toResponse(request, exception.message ?: NotificationErrorCode.NOT_FOUND.defaultMessage)

    @ExceptionHandler(NotificationInvalidCursorException::class, IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun invalid(exception: IllegalArgumentException, request: HttpServletRequest) =
        NotificationErrorCode.INVALID_REQUEST.toResponse(request, exception.message ?: NotificationErrorCode.INVALID_REQUEST.defaultMessage)
}

enum class NotificationErrorCode(override val code: String, override val defaultMessage: String) : ErrorCode {
    NOT_FOUND("NOTIFICATION_NOT_FOUND", "알림을 찾을 수 없습니다."),
    INVALID_REQUEST("INVALID_NOTIFICATION_REQUEST", "알림 요청이 올바르지 않습니다."),
}
