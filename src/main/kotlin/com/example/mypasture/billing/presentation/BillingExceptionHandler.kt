package com.example.mypasture.billing.presentation

import com.example.mypasture.billing.application.*
import com.example.mypasture.billing.domain.BillingConflictException
import com.example.mypasture.common.error.ApiErrorResponse
import com.example.mypasture.common.error.ErrorCode
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** 원문 스토어 응답과 구매 토큰은 숨기고 고정 오류 코드만 클라이언트에 반환합니다. */
@RestControllerAdvice(basePackageClasses = [BillingController::class])
class BillingExceptionHandler {
    @ExceptionHandler(BillingNotEnabled::class, BillingRequestNotFound::class, BillingConflictException::class,
        StorePurchaseRejected::class, StoreTemporarilyUnavailable::class)
    fun handle(error: RuntimeException, request: HttpServletRequest): ResponseEntity<ApiErrorResponse> {
        val status = when (error) {
            is BillingNotEnabled, is StoreTemporarilyUnavailable -> 503
            is BillingRequestNotFound -> 404
            is BillingConflictException -> 409
            is StorePurchaseRejected -> when (error.reason) {
                "UNAUTHENTICATED_WEBHOOK", "INVALID_APPLE_SIGNATURE" -> 401
                "PURCHASE_REQUEST_LIMIT" -> 429
                else -> 422
            }
            else -> 500
        }
        val code = when (error) {
            is BillingNotEnabled -> "BILLING_NOT_ENABLED"
            is BillingRequestNotFound -> "BILLING_REQUEST_NOT_FOUND"
            is BillingConflictException -> "BILLING_CONFLICT"
            is StorePurchaseRejected -> error.reason
            else -> "STORE_TEMPORARILY_UNAVAILABLE"
        }
        return ResponseEntity.status(status).body(ApiErrorResponse.of(BillingApiError(code), request))
    }
}

/** 검증 실패 세부사항 대신 구매 상태 재확인을 안내하는 공통 응답 코드입니다. */
data class BillingApiError(override val code: String) : ErrorCode {
    override val defaultMessage = "구매를 확인할 수 없습니다. 구매 상태를 확인하거나 잠시 후 다시 시도해 주세요."
}
