package com.example.mypasture.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** `app.oauth` 설정값을 바인딩해 소셜 로그인 제공자별 검증 정보를 관리하는 configuration properties 클래스입니다. */
@ConfigurationProperties("app.oauth")
data class OAuthProperties(
    val google: OidcProviderProperties = OidcProviderProperties(
        issuer = "https://accounts.google.com",
        jwkSetUri = "https://www.googleapis.com/oauth2/v3/certs",
    ),
    val apple: OidcProviderProperties = OidcProviderProperties(
        issuer = "https://appleid.apple.com",
        jwkSetUri = "https://appleid.apple.com/auth/keys",
    ),
    val kakao: KakaoProviderProperties = KakaoProviderProperties(),
)

/** Google/Apple처럼 OIDC identity token을 검증하는 제공자의 설정입니다. */
data class OidcProviderProperties(
    val clientId: String = "",
    val issuer: String = "",
    val jwkSetUri: String = "",
)

/** Kakao access token으로 사용자 정보를 조회할 때 사용하는 설정입니다. */
data class KakaoProviderProperties(
    val userInfoUri: String = "https://kapi.kakao.com/v2/user/me",
)
