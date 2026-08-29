package com.example.mypasture.config

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Redis 원자 연산으로 인증 brute force, 짧은 참여 코드 추측, AI 과다 호출을 제한합니다. */
@Component
class RedisRequestRateLimitFilter(
    private val redis: StringRedisTemplate,
    private val properties: RateLimitProperties,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !properties.enabled || request.method == HttpMethod.OPTIONS.name() || policyFor(request) == null

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val policy = policyFor(request) ?: return filterChain.doFilter(request, response)
        val identity = if (policy.authenticatedScope) authenticatedUserId() ?: clientAddress(request) else clientAddress(request)
        val bucket = System.currentTimeMillis() / (properties.windowSeconds * 1_000)
        val key = "rate:${policy.scope}:${digest(identity)}:$bucket"

        val count = runCatching {
            redis.execute(INCREMENT_SCRIPT, listOf(key), properties.windowSeconds.toString())
        }.onFailure { rateLimitLogger.error("Rate limit store is unavailable", it) }.getOrNull()

        if (count == null) {
            if (properties.failClosed) writeError(response, 503, "RATE_LIMIT_UNAVAILABLE", "요청 보호 서비스를 사용할 수 없습니다.")
            else filterChain.doFilter(request, response)
            return
        }
        if (count > policy.limit) {
            response.setHeader("Retry-After", properties.windowSeconds.toString())
            writeError(response, 429, "RATE_LIMIT_EXCEEDED", "요청이 너무 많습니다. 잠시 후 다시 시도해주세요.")
            return
        }
        filterChain.doFilter(request, response)
    }

    private fun policyFor(request: HttpServletRequest): Policy? = when {
        request.requestURI.startsWith("/api/v1/auth/") && request.method == HttpMethod.POST.name() ->
            Policy("auth", properties.authRequestsPerWindow)
        request.requestURI.startsWith("/api/v1/ai/") ->
            Policy("ai", properties.aiRequestsPerWindow, authenticatedScope = true)
        request.requestURI == "/api/v1/quiet-time/join-codes/resolve" && request.method == HttpMethod.POST.name() ->
            Policy("quiet-time-code", properties.quietTimeJoinCodeRequestsPerWindow, authenticatedScope = true)
        else -> null
    }

    private fun authenticatedUserId(): String? =
        (SecurityContextHolder.getContext().authentication?.principal as? JwtPrincipal)?.userId?.toString()

    private fun clientAddress(request: HttpServletRequest): String {
        if (!properties.trustForwardedHeaders) return request.remoteAddr
        return request.getHeader("X-Forwarded-For")
            ?.substringBefore(',')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: request.remoteAddr
    }

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun writeError(response: HttpServletResponse, status: Int, code: String, message: String) {
        response.status = status
        response.characterEncoding = StandardCharsets.UTF_8.name()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.writer.write("{\"code\":\"$code\",\"message\":\"$message\"}")
    }

    private data class Policy(val scope: String, val limit: Long, val authenticatedScope: Boolean = false)

    companion object {
        private val rateLimitLogger = LoggerFactory.getLogger(RedisRequestRateLimitFilter::class.java)
        private val INCREMENT_SCRIPT = DefaultRedisScript(
            "local count = redis.call('INCR', KEYS[1]); " +
                "if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]); end; return count",
            Long::class.java,
        )
    }
}
