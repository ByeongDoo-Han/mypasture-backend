package com.example.mypasture.bible.presentation

import com.example.mypasture.auth.application.InvalidCredentialsException
import com.example.mypasture.bible.application.*
import com.example.mypasture.common.error.CommonErrorCode
import com.example.mypasture.common.error.ErrorCode
import com.example.mypasture.common.error.toResponse
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.*

/** 필사 도메인의 실패를 개인정보 없는 안정적인 오류 코드로 변환합니다. */
@RestControllerAdvice(assignableTypes = [ScriptureCopyController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class ScriptureCopyExceptionHandler {
    @ExceptionHandler(ScriptureCopyNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun notFound(request: HttpServletRequest) = ScriptureCopyErrorCode.NOT_FOUND.toResponse(request)

    @ExceptionHandler(ScriptureCopyConflictException::class, DataIntegrityViolationException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun conflict(request: HttpServletRequest) = ScriptureCopyErrorCode.CONFLICT.toResponse(request)

    @ExceptionHandler(ScriptureCopyIncompleteException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun incomplete(request: HttpServletRequest) = ScriptureCopyErrorCode.INCOMPLETE.toResponse(request)

    @ExceptionHandler(BibleNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun bibleNotFound(request: HttpServletRequest) = BibleErrorCode.NOT_FOUND.toResponse(request)

    @ExceptionHandler(IllegalArgumentException::class, HttpMessageNotReadableException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun invalid(request: HttpServletRequest) = CommonErrorCode.INVALID_REQUEST.toResponse(request)

    @ExceptionHandler(InvalidCredentialsException::class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    fun inactive(request: HttpServletRequest) = CommonErrorCode.UNAUTHORIZED.toResponse(request)
}

/** 필사 API가 외부에 제공하는 오류 식별자입니다. */
enum class ScriptureCopyErrorCode(override val code: String, override val defaultMessage: String) : ErrorCode {
    NOT_FOUND("SCRIPTURE_COPY_NOT_FOUND", "필사 기록을 찾을 수 없습니다."),
    CONFLICT("SCRIPTURE_COPY_CONFLICT", "필사 기록이 변경되었거나 이미 완료되었습니다. 최신 기록을 확인해 주세요."),
    INCOMPLETE("SCRIPTURE_COPY_INCOMPLETE", "말씀의 모든 글자를 옮겨 적은 뒤 완료해 주세요."),
}
