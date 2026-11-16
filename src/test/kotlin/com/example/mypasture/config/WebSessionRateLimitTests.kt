package com.example.mypasture.config

import com.example.mypasture.auth.presentation.WebSessionCookie
import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.mock.web.MockHttpServletRequest
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 비로그인 공개 화면의 세션 탐색이 로그인 시도 한도를 소모하지 않게 검증합니다. */
class WebSessionRateLimitTests {
    @Test
    fun `익명 확인 요청은 제한을 건너뛰고 쿠키를 통한 갱신은 횟수를 제한한다`() {
        val filter = RedisRequestRateLimitFilter(mock(StringRedisTemplate::class.java), RateLimitProperties(enabled = true))
        val request = MockHttpServletRequest("POST", "/api/v1/auth/web/refresh")
        assertTrue(skips(filter, request))
        request.setCookies(Cookie(WebSessionCookie.NAME, "test-refresh"))
        assertFalse(skips(filter, request))
        request.setRequestURI("/api/v1/auth/web/session")
        assertFalse(skips(filter, request))
    }

    private fun skips(filter: RedisRequestRateLimitFilter, request: HttpServletRequest): Boolean {
        val method = RedisRequestRateLimitFilter::class.java.getDeclaredMethod("shouldNotFilter", HttpServletRequest::class.java)
        method.isAccessible = true
        return method.invoke(filter, request) as Boolean
    }
}
