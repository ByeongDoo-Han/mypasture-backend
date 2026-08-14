package com.example.mypasture.bible.presentation

import com.example.mypasture.bible.application.BibleCommentaryConflictException
import com.example.mypasture.bible.application.BibleCommentaryNotFoundException
import com.example.mypasture.bible.application.BibleNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

/** 성경 본문과 해설 도메인 예외를 일관된 HTTP 응답으로 변환합니다. */
@RestControllerAdvice(assignableTypes = [BibleController::class, BibleCommentaryAdminController::class])
class BibleExceptionHandler {
    @ExceptionHandler(BibleNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleBibleNotFound(exception: BibleNotFoundException): BibleErrorResponse =
        BibleErrorResponse("BIBLE_NOT_FOUND", exception.message ?: "성경 본문을 찾을 수 없습니다.")

    @ExceptionHandler(BibleCommentaryNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun handleCommentaryNotFound(exception: BibleCommentaryNotFoundException): BibleErrorResponse =
        BibleErrorResponse("BIBLE_COMMENTARY_NOT_FOUND", exception.message ?: "게시된 해설을 찾을 수 없습니다.")

    @ExceptionHandler(BibleCommentaryConflictException::class, IllegalStateException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleConflict(exception: RuntimeException): BibleErrorResponse =
        BibleErrorResponse("BIBLE_COMMENTARY_CONFLICT", exception.message ?: "해설 상태가 요청과 맞지 않습니다.")

    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleBadRequest(exception: IllegalArgumentException): BibleErrorResponse =
        BibleErrorResponse("INVALID_BIBLE_REQUEST", exception.message ?: "성경 조회 요청이 올바르지 않습니다.")
}
