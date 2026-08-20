package com.example.mypasture.common.error

import jakarta.servlet.http.HttpServletRequest
import java.time.Instant

/** 모든 REST API가 공유하는 오류 응답 계약입니다. */
data class ApiErrorResponse(
    val code: String,
    val message: String,
    val timestamp: Instant,
    val path: String?,
    val traceId: String?,
    val fieldErrors: List<FieldErrorResponse> = emptyList(),
) {
    companion object {
        fun of(
            errorCode: ErrorCode,
            request: HttpServletRequest? = null,
            message: String = errorCode.defaultMessage,
            fieldErrors: List<FieldErrorResponse> = emptyList(),
        ): ApiErrorResponse =
            ApiErrorResponse(
                code = errorCode.code,
                message = message,
                timestamp = Instant.now(),
                path = request?.requestURI,
                traceId = request?.getAttribute(RequestCorrelationFilter.TRACE_ID_ATTRIBUTE)?.toString(),
                fieldErrors = fieldErrors,
            )
    }
}

/** Bean Validation 실패 필드와 사용자에게 노출할 검증 메시지입니다. */
data class FieldErrorResponse(
    val field: String,
    val message: String,
)

/** 도메인 오류 코드를 공통 응답으로 변환하는 presentation 계층용 편의 함수입니다. */
fun ErrorCode.toResponse(
    request: HttpServletRequest,
    message: String = defaultMessage,
): ApiErrorResponse = ApiErrorResponse.of(this, request, message)
