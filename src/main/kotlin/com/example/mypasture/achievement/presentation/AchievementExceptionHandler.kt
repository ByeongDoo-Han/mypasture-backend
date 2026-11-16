package com.example.mypasture.achievement.presentation

import com.example.mypasture.common.error.CommonErrorCode
import com.example.mypasture.common.error.toResponse
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

/** 업적의 입력 검증 실패만 공통 오류 형식의 400 응답으로 변환합니다. */
@RestControllerAdvice(assignableTypes = [AchievementController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class AchievementExceptionHandler {
    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun invalidRequest(request: HttpServletRequest) = CommonErrorCode.INVALID_REQUEST.toResponse(request)
}
