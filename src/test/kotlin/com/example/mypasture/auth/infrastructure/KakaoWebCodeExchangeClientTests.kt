package com.example.mypasture.auth.infrastructure

import com.example.mypasture.config.KakaoProviderProperties
import com.example.mypasture.config.KakaoWebProperties
import com.example.mypasture.config.OAuthProperties
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 카카오 웹 인가 코드 교환 요청과 비밀값을 숨긴 오류 처리를 검증합니다. */
class KakaoWebCodeExchangeClientTests {
    private val verifier = "A".repeat(43)
    private val properties = OAuthProperties(
        kakao = KakaoProviderProperties(
            appIds = listOf(123456789),
            web = KakaoWebProperties(
                restApiKey = "test-rest-key",
                clientSecret = "test-client-secret",
                redirectUri = "https://app.mypasture.app/oauth/kakao",
            ),
        ),
    )

    @Test
    fun `exchange posts fixed redirect and server secret with PKCE verifier`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://kauth.kakao.com/oauth/token"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
            .andExpect(content().string(containsString("client_secret=test-client-secret")))
            .andExpect(content().string(containsString("redirect_uri=https%3A%2F%2Fapp.mypasture.app%2Foauth%2Fkakao")))
            .andExpect(content().string(containsString("code_verifier=$verifier")))
            .andRespond(withSuccess("""{"access_token":"issued-access-token"}""", MediaType.APPLICATION_JSON))

        assertEquals("issued-access-token", KakaoWebCodeExchangeClient(properties, builder).exchange("one-time-code", verifier))
        server.verify()
    }

    @Test
    fun `exchange rejects provider errors without exposing response details`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://kauth.kakao.com/oauth/token"))
            .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("sensitive-provider-error"))

        val failure = assertFailsWith<InvalidSocialTokenException> {
            KakaoWebCodeExchangeClient(properties, builder).exchange("reused-code", verifier)
        }
        assertEquals("Kakao authorization code is invalid", failure.message)
        server.verify()
    }

    @Test
    fun `web readiness stays disabled without secret or HTTPS callback`() {
        assertEquals(false, KakaoWebProperties(restApiKey = "test-key", redirectUri = "https://app.mypasture.app/oauth/kakao").isConfigured())
        assertEquals(false, KakaoWebProperties(restApiKey = "test-key", clientSecret = "secret", redirectUri = "http://example.com").isConfigured())
    }
}
