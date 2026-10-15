package com.example.mypasture.auth.infrastructure

import com.example.mypasture.auth.application.KakaoWebCodeExchange
import com.example.mypasture.config.OAuthProperties
import com.fasterxml.jackson.databind.JsonNode
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import java.time.Duration

/** 서버에만 보관한 시크릿과 PKCE verifier로 카카오 인가 코드를 교환합니다. */
@Component
class KakaoWebCodeExchangeClient private constructor(
    private val properties: OAuthProperties,
    private val restClient: RestClient,
) : KakaoWebCodeExchange {
    @Autowired
    constructor(properties: OAuthProperties) : this(
        properties,
        RestClient.builder().requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(Duration.ofMillis(properties.connectTimeoutMs))
                setReadTimeout(Duration.ofMillis(properties.readTimeoutMs))
            },
        ).build(),
    )

    internal constructor(properties: OAuthProperties, builder: RestClient.Builder) : this(properties, builder.build())

    override fun exchange(code: String, codeVerifier: String): String {
        val config = properties.kakao.web
        if (!config.isConfigured() || properties.kakao.appIds.isEmpty()) {
            throw SocialOAuthProviderMisconfiguredException("Kakao web login is not configured")
        }
        val form = LinkedMultiValueMap<String, String>().apply {
            add("grant_type", "authorization_code")
            add("client_id", config.restApiKey)
            add("client_secret", config.clientSecret)
            add("redirect_uri", config.redirectUri)
            add("code", code)
            add("code_verifier", codeVerifier)
        }
        val token = try {
            restClient.post()
                .uri(config.tokenUri)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(JsonNode::class.java)
        } catch (_: RuntimeException) {
            throw InvalidSocialTokenException("Kakao authorization code is invalid")
        }
        return token?.path("access_token")?.asText()?.takeIf { it.isNotBlank() }
            ?: throw InvalidSocialTokenException("Kakao authorization code is invalid")
    }
}
