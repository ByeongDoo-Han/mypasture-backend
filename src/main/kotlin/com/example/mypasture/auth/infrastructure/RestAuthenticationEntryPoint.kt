package com.example.mypasture.auth.infrastructure

import com.example.mypasture.common.error.ApiErrorResponse
import com.example.mypasture.common.error.CommonErrorCode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.stereotype.Component

/** 인증 실패를 모바일 클라이언트가 처리하기 쉬운 JSON 응답으로 변환하는 entry point입니다. */
@Component
class RestAuthenticationEntryPoint(
    private val objectMapper: ObjectMapper,
) : AuthenticationEntryPoint {
    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        response.status = HttpStatus.UNAUTHORIZED.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        objectMapper.writeValue(
            response.writer,
            ApiErrorResponse.of(
                errorCode = CommonErrorCode.UNAUTHORIZED,
                request = request,
                message = request.getAttribute(JwtAuthenticationFilter.JWT_ERROR_ATTRIBUTE)?.toString()
                    ?: CommonErrorCode.UNAUTHORIZED.defaultMessage,
            ),
        )
    }
}
