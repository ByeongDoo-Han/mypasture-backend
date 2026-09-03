package com.example.mypasture.moderation.presentation

import com.example.mypasture.common.error.ApiErrorResponse
import com.example.mypasture.common.error.ErrorCode
import com.example.mypasture.moderation.application.ModerationInvalidRequestException
import com.example.mypasture.moderation.application.ModerationOperationConflictException
import com.example.mypasture.moderation.application.ModerationReportNotFoundException
import com.example.mypasture.moderation.application.ModerationTargetUnavailableException
import com.example.mypasture.moderation.application.ModerationUserUnavailableException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** 모바일과 어드민이 신고 실패 원인을 안정적으로 분기할 공통 오류 코드입니다. */
enum class ModerationErrorCode(override val code: String, override val defaultMessage: String) : ErrorCode {
    INVALID_REQUEST("MODERATION_INVALID_REQUEST", "신고 또는 차단 요청을 확인해 주세요."),
    TARGET_UNAVAILABLE("MODERATION_TARGET_UNAVAILABLE", "신고할 수 있는 콘텐츠를 찾을 수 없습니다."),
    USER_UNAVAILABLE("MODERATION_USER_UNAVAILABLE", "차단할 사용자를 찾을 수 없습니다."),
    REPORT_NOT_FOUND("MODERATION_REPORT_NOT_FOUND", "신고 내역을 찾을 수 없습니다."),
    OPERATION_CONFLICT("MODERATION_OPERATION_CONFLICT", "같은 operationId를 다른 처리 요청에 사용할 수 없습니다."),
}

/** 신고·차단 정책 오류를 앱과 어드민이 분기 가능한 공통 오류 응답으로 변환합니다. */
@RestControllerAdvice(assignableTypes = [ModerationController::class, ModerationAdminController::class])
class ModerationExceptionHandler {
    @ExceptionHandler(ModerationInvalidRequestException::class)
    fun invalid(error: ModerationInvalidRequestException, request: HttpServletRequest) =
        response(HttpStatus.BAD_REQUEST, ModerationErrorCode.INVALID_REQUEST, error, request)

    @ExceptionHandler(ModerationTargetUnavailableException::class)
    fun target(error: ModerationTargetUnavailableException, request: HttpServletRequest) =
        response(HttpStatus.NOT_FOUND, ModerationErrorCode.TARGET_UNAVAILABLE, error, request)

    @ExceptionHandler(ModerationUserUnavailableException::class)
    fun user(error: ModerationUserUnavailableException, request: HttpServletRequest) =
        response(HttpStatus.NOT_FOUND, ModerationErrorCode.USER_UNAVAILABLE, error, request)

    @ExceptionHandler(ModerationReportNotFoundException::class)
    fun report(error: ModerationReportNotFoundException, request: HttpServletRequest) =
        response(HttpStatus.NOT_FOUND, ModerationErrorCode.REPORT_NOT_FOUND, error, request)

    @ExceptionHandler(ModerationOperationConflictException::class)
    fun conflict(error: ModerationOperationConflictException, request: HttpServletRequest) =
        response(HttpStatus.CONFLICT, ModerationErrorCode.OPERATION_CONFLICT, error, request)

    private fun response(status: HttpStatus, code: ModerationErrorCode, error: RuntimeException, request: HttpServletRequest) =
        ResponseEntity.status(status).body(ApiErrorResponse.of(code, request, error.message ?: code.defaultMessage))
}
