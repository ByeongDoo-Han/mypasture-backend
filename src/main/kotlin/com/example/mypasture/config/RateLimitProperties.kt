package com.example.mypasture.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** 인증·AI·짧은 참여 코드 API의 요청 한도와 운영 장애 정책을 관리합니다. */
@ConfigurationProperties("app.security.rate-limit")
data class RateLimitProperties(
    val enabled: Boolean = false,
    val failClosed: Boolean = false,
    val trustForwardedHeaders: Boolean = false,
    val windowSeconds: Long = 60,
    val authRequestsPerWindow: Long = 20,
    val aiRequestsPerWindow: Long = 15,
    val quietTimeJoinCodeRequestsPerWindow: Long = 12,
)
