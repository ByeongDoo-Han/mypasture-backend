package com.example.mypasture.auth.infrastructure

import com.example.mypasture.auth.application.SocialLoginCommand
import com.example.mypasture.auth.application.SocialOAuthClient
import com.example.mypasture.auth.application.SocialUserProfile
import com.example.mypasture.auth.domain.AuthProvider
import com.example.mypasture.config.OAuthProperties
import com.fasterxml.jackson.databind.JsonNode
import org.springframework.http.HttpHeaders
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Duration

/** Google OIDC id token을 검증하고 사용자 프로필을 추출하는 infrastructure adapter입니다. */
@Component
class GoogleOidcOAuthClient(
    private val oauthProperties: OAuthProperties,
) : SocialOAuthClient {
    override val provider: AuthProvider = AuthProvider.GOOGLE
    private val decoder by lazy { NimbusJwtDecoder.withJwkSetUri(oauthProperties.google.jwkSetUri).build() }

    override fun fetchProfile(command: SocialLoginCommand): SocialUserProfile {
        val jwt = decodeOidcToken(
            idToken = command.idToken ?: throw MissingSocialTokenException("Google idToken is required"),
            decoder = decoder,
            expectedIssuer = oauthProperties.google.issuer,
            expectedAudiences = oauthProperties.google.acceptedClientIds(),
        )
        return SocialUserProfile(
            provider = provider,
            providerUserId = jwt.subject,
            email = jwt.claimAsString("email")?.lowercase(),
            emailVerified = jwt.claimAsBoolean("email_verified"),
            displayName = jwt.claimAsString("name") ?: command.displayNameHint,
            nonce = jwt.claimAsString("nonce"),
        )
    }
}

/** Apple identity token을 검증하고 private relay email을 포함한 사용자 프로필을 추출하는 infrastructure adapter입니다. */
@Component
class AppleOidcOAuthClient(
    private val oauthProperties: OAuthProperties,
) : SocialOAuthClient {
    override val provider: AuthProvider = AuthProvider.APPLE
    private val decoder by lazy { NimbusJwtDecoder.withJwkSetUri(oauthProperties.apple.jwkSetUri).build() }

    override fun fetchProfile(command: SocialLoginCommand): SocialUserProfile {
        val jwt = decodeOidcToken(
            idToken = command.idToken ?: throw MissingSocialTokenException("Apple idToken is required"),
            decoder = decoder,
            expectedIssuer = oauthProperties.apple.issuer,
            expectedAudiences = oauthProperties.apple.acceptedClientIds(),
        )
        return SocialUserProfile(
            provider = provider,
            providerUserId = jwt.subject,
            email = jwt.claimAsString("email")?.lowercase(),
            emailVerified = jwt.claimAsBoolean("email_verified"),
            displayName = command.displayNameHint ?: "Apple User",
            nonce = jwt.claimAsString("nonce"),
        )
    }
}

/** Kakao access token으로 `/v2/user/me`를 호출해 사용자 프로필을 조회하는 infrastructure adapter입니다. */
@Component
class KakaoOAuthClient(
    private val oauthProperties: OAuthProperties,
) : SocialOAuthClient {
    override val provider: AuthProvider = AuthProvider.KAKAO
    private val restClient = RestClient.builder()
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(Duration.ofMillis(oauthProperties.connectTimeoutMs))
                setReadTimeout(Duration.ofMillis(oauthProperties.readTimeoutMs))
            },
        )
        .build()

    override fun fetchProfile(command: SocialLoginCommand): SocialUserProfile {
        val accessToken = command.accessToken ?: throw MissingSocialTokenException("Kakao accessToken is required")
        val acceptedAppIds = oauthProperties.kakao.appIds.toSet()
        if (acceptedAppIds.isEmpty()) throw SocialOAuthProviderMisconfiguredException("Kakao app id is required")
        val tokenInfo = get(accessToken, oauthProperties.kakao.tokenInfoUri, "Kakao access token info is invalid")
        val appId = tokenInfo.path("app_id").asLong(-1)
        if (appId !in acceptedAppIds || tokenInfo.path("expires_in").asLong(0) <= 0) {
            throw InvalidSocialTokenException("Kakao access token is not issued for this app")
        }
        val tokenUserId = tokenInfo.path("id").asText().takeIf { it.isNotBlank() }
            ?: throw InvalidSocialTokenException("Kakao token subject is missing")
        val response = try {
            restClient.get()
                .uri(oauthProperties.kakao.userInfoUri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
                .retrieve()
                .body(JsonNode::class.java)
        } catch (ex: RuntimeException) {
            throw InvalidSocialTokenException("Kakao access token is invalid")
        } ?: throw InvalidSocialTokenException("Kakao user info response is empty")

        val account = response.path("kakao_account")
        val profile = account.path("profile")
        val profileUserId = response.path("id").asText().takeIf { it.isNotBlank() }
            ?: throw InvalidSocialTokenException("Kakao subject is missing")
        if (profileUserId != tokenUserId) throw InvalidSocialTokenException("Kakao token subject does not match profile")
        return SocialUserProfile(
            provider = provider,
            providerUserId = profileUserId,
            email = account.path("email").asText(null)?.lowercase(),
            emailVerified = account.path("is_email_verified").asBoolean(false),
            displayName = profile.path("nickname").asText(null) ?: command.displayNameHint,
        )
    }

    private fun get(accessToken: String, uri: String, failureMessage: String): JsonNode =
        try {
            restClient.get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
                .retrieve()
                .body(JsonNode::class.java)
                ?: throw InvalidSocialTokenException(failureMessage)
        } catch (error: InvalidSocialTokenException) {
            throw error
        } catch (error: RuntimeException) {
            throw InvalidSocialTokenException(failureMessage)
        }
}

private fun decodeOidcToken(
    idToken: String,
    decoder: NimbusJwtDecoder,
    expectedIssuer: String,
    expectedAudiences: List<String>,
): Jwt {
    val jwt = try {
        decoder.decode(idToken)
    } catch (ex: JwtException) {
        throw InvalidSocialTokenException("OIDC id token is invalid")
    }

    if (jwt.issuer?.toString() != expectedIssuer) throw InvalidSocialTokenException("OIDC issuer is invalid")
    val configuredAudiences = expectedAudiences.map { it.trim() }.filter { it.isNotBlank() }.toSet()
    if (configuredAudiences.isEmpty()) throw SocialOAuthProviderMisconfiguredException("OIDC client id is required")
    if (jwt.audience.none { it in configuredAudiences }) throw InvalidSocialTokenException("OIDC audience is invalid")
    if (jwt.subject.isNullOrBlank()) throw InvalidSocialTokenException("OIDC subject is missing")
    return jwt
}

private fun Jwt.claimAsString(name: String): String? = getClaimAsString(name)?.takeIf { it.isNotBlank() }

private fun Jwt.claimAsBoolean(name: String): Boolean =
    when (val value = claims[name]) {
        is Boolean -> value
        is String -> value.equals("true", ignoreCase = true)
        else -> false
    }

/** 소셜 로그인 요청에 필요한 제공자 토큰이 없을 때 발생하는 예외입니다. */
class MissingSocialTokenException(message: String) : RuntimeException(message)

/** 제공자 토큰 검증 또는 사용자 정보 조회가 실패했을 때 발생하는 예외입니다. */
class InvalidSocialTokenException(message: String) : RuntimeException(message)

/** 소셜 로그인 제공자 설정이 누락되어 토큰을 안전하게 검증할 수 없을 때 발생하는 예외입니다. */
class SocialOAuthProviderMisconfiguredException(message: String) : RuntimeException(message)
