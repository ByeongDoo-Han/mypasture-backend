package com.example.mypasture.auth.presentation

import com.example.mypasture.auth.application.InvalidAdminEmailCodeException
import com.example.mypasture.common.error.ErrorCode
import com.example.mypasture.common.error.toResponse
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** 관리자 OTP의 모든 인증 실패를 계정 정보를 숨긴 같은 401 응답으로 변환합니다. */
@RestControllerAdvice(assignableTypes = [AdminEmailLoginController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class AdminEmailLoginExceptionHandler {
    @ExceptionHandler(InvalidAdminEmailCodeException::class)
    fun invalidCode(request: HttpServletRequest) = ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .cacheControl(CacheControl.noStore()).body(AdminEmailLoginError.INVALID_ADMIN_EMAIL_CODE.toResponse(request))
}

/** 관리자 이메일 인증에서 노출하는 단일 실패 코드입니다. */
enum class AdminEmailLoginError(override val code: String, override val defaultMessage: String) : ErrorCode {
    INVALID_ADMIN_EMAIL_CODE("INVALID_ADMIN_EMAIL_CODE", "인증번호가 만료되었거나 올바르지 않습니다. 다시 요청해 주세요."),
}
