package com.example.mypasture.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** 모바일 웹과 관리자에서 백엔드 API를 호출할 수 있는 허용 출처를 관리합니다. */
@ConfigurationProperties("app.cors")
data class CorsProperties(
    val allowedOrigins: List<String> = emptyList(),
    val webSessionAllowedOrigins: List<String> = emptyList(),
)
