package com.example.mypasture.common.error

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/** 요청마다 추적 ID를 생성해 오류 응답, 로그, 응답 헤더를 서로 연결합니다. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestCorrelationFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val traceId = UUID.randomUUID().toString()
        request.setAttribute(TRACE_ID_ATTRIBUTE, traceId)
        response.setHeader(TRACE_ID_HEADER, traceId)
        MDC.put(MDC_TRACE_ID_KEY, traceId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(MDC_TRACE_ID_KEY)
        }
    }

    companion object {
        const val TRACE_ID_ATTRIBUTE = "mypasture.traceId"
        const val TRACE_ID_HEADER = "X-Request-Id"
        const val MDC_TRACE_ID_KEY = "traceId"
    }
}
