package com.example.mypasture.common.error

/** API 오류 코드가 제공해야 하는 안정적인 식별자와 기본 사용자 메시지 계약입니다. */
interface ErrorCode {
    val code: String
    val defaultMessage: String
}

/** Spring MVC와 서버 공통 처리 과정에서 발생하는 오류 코드입니다. */
enum class CommonErrorCode(
    override val code: String,
    override val defaultMessage: String,
) : ErrorCode {
    INVALID_REQUEST("INVALID_REQUEST", "요청 값이 올바르지 않습니다."),
    MALFORMED_REQUEST("MALFORMED_REQUEST", "요청 본문을 해석할 수 없습니다."),
    METHOD_NOT_ALLOWED("METHOD_NOT_ALLOWED", "지원하지 않는 HTTP 메서드입니다."),
    RESOURCE_NOT_FOUND("RESOURCE_NOT_FOUND", "요청한 리소스를 찾을 수 없습니다."),
    UNAUTHORIZED("UNAUTHORIZED", "인증이 필요합니다."),
    ACCESS_DENIED("ACCESS_DENIED", "요청한 작업을 수행할 권한이 없습니다."),
    INTERNAL_SERVER_ERROR("INTERNAL_SERVER_ERROR", "서버에서 요청을 처리하지 못했습니다."),
}
