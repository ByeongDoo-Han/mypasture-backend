package com.example.mypasture.policy.presentation

import com.example.mypasture.common.error.ErrorCode
import com.example.mypasture.common.error.toResponse
import com.example.mypasture.policy.application.PolicyNotFoundException
import com.example.mypasture.policy.application.RequiredPolicyConsentException
import com.example.mypasture.policy.application.RequiredPolicyWithdrawalException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

/** 정책 도메인 오류를 안정적인 공통 API 오류 응답으로 변환합니다. */
@RestControllerAdvice(assignableTypes = [PolicyController::class, com.example.mypasture.auth.presentation.AuthController::class, com.example.mypasture.ai.presentation.AiController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class PolicyExceptionHandler {
    @ExceptionHandler(PolicyNotFoundException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun notFound(request: HttpServletRequest) = PolicyErrorCode.POLICY_NOT_FOUND.toResponse(request)

    @ExceptionHandler(RequiredPolicyConsentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun consentRequired(request: HttpServletRequest) = PolicyErrorCode.POLICY_CONSENT_REQUIRED.toResponse(request)

    @ExceptionHandler(RequiredPolicyWithdrawalException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun withdrawalRejected(request: HttpServletRequest) = PolicyErrorCode.REQUIRED_POLICY_WITHDRAWAL_NOT_ALLOWED.toResponse(request)
}

enum class PolicyErrorCode(override val code: String, override val defaultMessage: String) : ErrorCode {
    POLICY_NOT_FOUND("POLICY_NOT_FOUND", "요청한 정책 문서를 찾을 수 없습니다."),
    POLICY_CONSENT_REQUIRED("POLICY_CONSENT_REQUIRED", "현재 필수 약관에 동의해 주세요."),
    REQUIRED_POLICY_WITHDRAWAL_NOT_ALLOWED("REQUIRED_POLICY_WITHDRAWAL_NOT_ALLOWED", "필수 동의는 서비스 이용 중 철회할 수 없습니다."),
}
