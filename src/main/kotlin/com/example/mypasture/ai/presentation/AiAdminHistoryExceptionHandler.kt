package com.example.mypasture.ai.presentation

import com.example.mypasture.ai.application.AiHistoryNotFoundException
import com.example.mypasture.common.error.ApiErrorResponse
import com.example.mypasture.common.error.toResponse
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/** 관리자 기록 조회 오류에도 원문과 커서를 노출하지 않습니다. */
@RestControllerAdvice(assignableTypes = [AiAdminHistoryController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class AiAdminHistoryExceptionHandler {
    @ExceptionHandler(AiHistoryNotFoundException::class)
    fun notFound(request: HttpServletRequest): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).cacheControl(CacheControl.noStore())
            .body(AiHistoryErrorCode.NOT_FOUND.toResponse(request))

    @ExceptionHandler(IllegalArgumentException::class, MethodArgumentTypeMismatchException::class)
    fun invalidRequest(request: HttpServletRequest): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
            .body(AiHistoryErrorCode.INVALID_REQUEST.toResponse(request))
}
