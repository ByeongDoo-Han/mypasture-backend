package com.example.mypasture.ai.presentation

import com.example.mypasture.ai.application.AiHistoryInvalidCursorException
import com.example.mypasture.ai.application.AiHistoryNotFoundException
import com.example.mypasture.common.error.ApiErrorResponse
import com.example.mypasture.common.error.toResponse
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** 기록 조회 오류에서도 민감한 요청 경로를 캐시에 남기지 않습니다. */
@RestControllerAdvice(assignableTypes = [AiHistoryController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class AiHistoryExceptionHandler {
    @ExceptionHandler(AiHistoryNotFoundException::class)
    fun notFound(exception: AiHistoryNotFoundException, request: HttpServletRequest): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).cacheControl(CacheControl.noStore())
            .body(AiHistoryErrorCode.NOT_FOUND.toResponse(request, exception.message ?: "AI 기록을 찾을 수 없습니다."))

    @ExceptionHandler(AiHistoryInvalidCursorException::class, IllegalArgumentException::class, MethodArgumentTypeMismatchException::class)
    fun invalidRequest(request: HttpServletRequest): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
            .body(AiHistoryErrorCode.INVALID_REQUEST.toResponse(request, "AI 기록 조회 요청이 올바르지 않습니다."))
}

/** 앱이 구분할 수 있는 history 전용 오류 코드입니다. */
enum class AiHistoryErrorCode(
    override val code: String,
    override val defaultMessage: String,
) : com.example.mypasture.common.error.ErrorCode {
    NOT_FOUND("AI_HISTORY_NOT_FOUND", "AI 기록을 찾을 수 없습니다."),
    INVALID_REQUEST("AI_HISTORY_INVALID_REQUEST", "AI 기록 조회 요청이 올바르지 않습니다."),
}
