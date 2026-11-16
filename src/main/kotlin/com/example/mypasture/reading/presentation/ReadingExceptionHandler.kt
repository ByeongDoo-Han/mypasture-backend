package com.example.mypasture.reading.presentation

import com.example.mypasture.common.error.ApiErrorResponse
import com.example.mypasture.common.error.CommonErrorCode
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.DateTimeException

/** 읽기 범위와 시간대 검증 실패를 내부 오류 대신 잘못된 요청으로 반환합니다. */
@RestControllerAdvice(assignableTypes = [ReadingController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class ReadingExceptionHandler {
    @ExceptionHandler(IllegalArgumentException::class, DateTimeException::class)
    fun invalidRequest(request: HttpServletRequest): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.badRequest().body(ApiErrorResponse.of(CommonErrorCode.INVALID_REQUEST, request))
}
