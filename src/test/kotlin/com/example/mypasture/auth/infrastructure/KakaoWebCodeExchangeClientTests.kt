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
    fun `인가 코드 교환은 고정된 리다이렉트와 서버 시크릿 및 PKCE 검증값을 보낸다`() {
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
    fun `제공자 오류는 응답 상세를 노출하지 않고 거부한다`() {
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
    fun `시크릿이나 HTTPS 콜백이 없으면 웹 로그인 준비 상태를 비활성화한다`() {
        assertEquals(false, KakaoWebProperties(restApiKey = "test-key", redirectUri = "https://app.mypasture.app/oauth/kakao").isConfigured())
        assertEquals(false, KakaoWebProperties(restApiKey = "test-key", clientSecret = "secret", redirectUri = "http://example.com").isConfigured())
    }
}
