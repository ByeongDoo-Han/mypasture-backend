package com.example.mypasture.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** `app.security.jwt` 설정값을 바인딩하고 JWT 발급 정책을 중앙에서 관리하는 configuration properties 클래스입니다. */
@ConfigurationProperties("app.security.jwt")
data class JwtProperties(
    val issuer: String,
    val secret: String,
    val accessTokenTtlSeconds: Long,
)
