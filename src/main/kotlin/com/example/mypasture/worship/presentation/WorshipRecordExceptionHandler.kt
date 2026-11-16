package com.example.mypasture.worship.presentation

import com.example.mypasture.bible.application.BibleNotFoundException
import com.example.mypasture.common.error.ErrorCode
import com.example.mypasture.common.error.toResponse
import com.example.mypasture.worship.application.WorshipRecordConflictException
import com.example.mypasture.worship.application.WorshipRecordNotFoundException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

/** 예배 기록 오류를 일관된 HTTP 상태와 공개 오류 코드로 변환합니다. */
@RestControllerAdvice(assignableTypes = [WorshipRecordController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class WorshipRecordExceptionHandler {
    @ExceptionHandler(WorshipRecordNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun notFound(request: HttpServletRequest) = WorshipRecordErrorCode.NOT_FOUND.toResponse(request)

    @ExceptionHandler(WorshipRecordConflictException::class, ObjectOptimisticLockingFailureException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun conflict(request: HttpServletRequest) = WorshipRecordErrorCode.CONFLICT.toResponse(request)

    @ExceptionHandler(IllegalArgumentException::class, BibleNotFoundException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun invalid(exception: RuntimeException, request: HttpServletRequest) =
        WorshipRecordErrorCode.INVALID_REQUEST.toResponse(request, exception.message ?: WorshipRecordErrorCode.INVALID_REQUEST.defaultMessage)
}

/** 예배 기록 API의 안정적인 오류 코드입니다. */
enum class WorshipRecordErrorCode(override val code: String, override val defaultMessage: String) : ErrorCode {
    NOT_FOUND("WORSHIP_RECORD_NOT_FOUND", "예배 기록을 찾을 수 없습니다."),
    CONFLICT("WORSHIP_RECORD_CONFLICT", "다른 기기에서 예배 기록이 수정되었습니다."),
    INVALID_REQUEST("INVALID_WORSHIP_RECORD_REQUEST", "예배 기록 요청이 올바르지 않습니다."),
}
