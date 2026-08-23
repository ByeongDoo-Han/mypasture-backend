package com.example.mypasture.daily_word.presentation

import com.example.mypasture.common.error.ErrorCode
import com.example.mypasture.common.error.toResponse
import com.example.mypasture.daily_word.application.DailyWordConflictException
import com.example.mypasture.daily_word.application.DailyWordGenerationException
import com.example.mypasture.daily_word.application.DailyWordNotFoundException
import com.example.mypasture.daily_word.application.DailyWordNotPublishedException
import com.example.mypasture.daily_word.application.DailyWordOperationConflictException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

/** 오늘의 말씀 공개·관리자 예외를 공통 API 오류 응답으로 변환합니다. */
@RestControllerAdvice(assignableTypes = [DailyWordController::class, DailyWordAdminController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class DailyWordExceptionHandler {
    @ExceptionHandler(DailyWordNotPublishedException::class, DailyWordNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun notFound(exception: RuntimeException, request: HttpServletRequest) =
        DailyWordErrorCode.NOT_FOUND.toResponse(request, exception.message ?: DailyWordErrorCode.NOT_FOUND.defaultMessage)

    @ExceptionHandler(DailyWordConflictException::class, DailyWordOperationConflictException::class, IllegalStateException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun conflict(exception: RuntimeException, request: HttpServletRequest) =
        DailyWordErrorCode.CONFLICT.toResponse(request, exception.message ?: DailyWordErrorCode.CONFLICT.defaultMessage)

    @ExceptionHandler(DailyWordGenerationException::class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    fun generation(exception: RuntimeException, request: HttpServletRequest) =
        DailyWordErrorCode.SOURCE_MISMATCH.toResponse(request, exception.message ?: DailyWordErrorCode.SOURCE_MISMATCH.defaultMessage)

    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun invalid(exception: IllegalArgumentException, request: HttpServletRequest) =
        DailyWordErrorCode.INVALID_REQUEST.toResponse(request, exception.message ?: DailyWordErrorCode.INVALID_REQUEST.defaultMessage)
}

enum class DailyWordErrorCode(override val code: String, override val defaultMessage: String) : ErrorCode {
    NOT_FOUND("DAILY_WORD_NOT_PUBLISHED", "게시된 오늘의 말씀을 찾을 수 없습니다."),
    CONFLICT("DAILY_WORD_CONFLICT", "오늘의 말씀 상태가 요청과 맞지 않습니다."),
    SOURCE_MISMATCH("DAILY_WORD_SOURCE_MISMATCH", "오늘의 말씀과 성경 원문이 일치하지 않습니다."),
    INVALID_REQUEST("INVALID_DAILY_WORD_REQUEST", "오늘의 말씀 요청이 올바르지 않습니다."),
}
