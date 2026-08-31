package com.example.mypasture.bible.presentation

import com.example.mypasture.bible.application.BibleCommentaryConflictException
import com.example.mypasture.bible.application.BibleCommentaryNotFoundException
import com.example.mypasture.bible.application.BibleNoteConflictException
import com.example.mypasture.bible.application.BibleNoteNotFoundException
import com.example.mypasture.bible.application.BibleNotFoundException
import com.example.mypasture.common.error.ErrorCode
import com.example.mypasture.common.error.toResponse
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

/** 성경 본문과 해설 도메인 예외를 일관된 HTTP 응답으로 변환합니다. */
@RestControllerAdvice(
    assignableTypes = [BibleController::class, BibleBookmarkController::class, BibleCommentaryAdminController::class, BibleNoteController::class],
)
@Order(Ordered.HIGHEST_PRECEDENCE)
class BibleExceptionHandler {
    @ExceptionHandler(BibleNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleBibleNotFound(exception: BibleNotFoundException, request: HttpServletRequest) =
        BibleErrorCode.NOT_FOUND.toResponse(request, exception.message ?: BibleErrorCode.NOT_FOUND.defaultMessage)

    @ExceptionHandler(BibleCommentaryNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleCommentaryNotFound(exception: BibleCommentaryNotFoundException, request: HttpServletRequest) =
        BibleErrorCode.COMMENTARY_NOT_FOUND.toResponse(request, exception.message ?: BibleErrorCode.COMMENTARY_NOT_FOUND.defaultMessage)

    @ExceptionHandler(BibleNoteNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleNoteNotFound(request: HttpServletRequest) =
        BibleErrorCode.NOTE_NOT_FOUND.toResponse(request)

    @ExceptionHandler(BibleNoteConflictException::class, ObjectOptimisticLockingFailureException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleNoteConflict(request: HttpServletRequest) =
        BibleErrorCode.NOTE_CONFLICT.toResponse(request)

    @ExceptionHandler(BibleCommentaryConflictException::class, IllegalStateException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleConflict(exception: RuntimeException, request: HttpServletRequest) =
        BibleErrorCode.COMMENTARY_CONFLICT.toResponse(request, exception.message ?: BibleErrorCode.COMMENTARY_CONFLICT.defaultMessage)

    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleBadRequest(exception: IllegalArgumentException, request: HttpServletRequest) =
        BibleErrorCode.INVALID_REQUEST.toResponse(request, exception.message ?: BibleErrorCode.INVALID_REQUEST.defaultMessage)
}

/** 성경 도메인이 외부 클라이언트에 제공하는 안정적인 오류 코드입니다. */
enum class BibleErrorCode(
    override val code: String,
    override val defaultMessage: String,
) : ErrorCode {
    NOT_FOUND("BIBLE_NOT_FOUND", "성경 본문을 찾을 수 없습니다."),
    COMMENTARY_NOT_FOUND("BIBLE_COMMENTARY_NOT_FOUND", "게시된 해설을 찾을 수 없습니다."),
    COMMENTARY_CONFLICT("BIBLE_COMMENTARY_CONFLICT", "해설 상태가 요청과 맞지 않습니다."),
    NOTE_NOT_FOUND("BIBLE_NOTE_NOT_FOUND", "말씀 메모를 찾을 수 없습니다."),
    NOTE_CONFLICT("BIBLE_NOTE_CONFLICT", "다른 기기에서 메모가 변경되었습니다. 최신 내용을 불러와 다시 시도해 주세요."),
    INVALID_REQUEST("INVALID_BIBLE_REQUEST", "성경 조회 요청이 올바르지 않습니다."),
}
