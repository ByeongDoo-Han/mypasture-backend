package com.example.mypasture.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** 비밀번호 재설정 토큰 수명, 재요청 간격, 전달 방식을 관리하는 인증 보안 설정입니다. */
@ConfigurationProperties("app.auth.password-reset")
data class AuthSecurityProperties(
    val tokenTtlMinutes: Long = 30,
    val requestCooldownSeconds: Long = 60,
    val resetUrl: String = "mypasture://password-reset",
    val delivery: String = "log",
    val from: String = "no-reply@mypasture.app",
)
