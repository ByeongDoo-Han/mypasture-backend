package com.example.mypasture.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

/** 이메일 회원가입 인증의 만료, 재전송, 실패 횟수와 전달 방식을 관리합니다. */
@ConfigurationProperties("app.auth.email-verification")
@Validated
data class EmailVerificationProperties(
    val required: Boolean = true,
    @field:Min(1)
    val codeTtlMinutes: Long = 10,
    @field:Min(1)
    val signupTokenTtlMinutes: Long = 30,
    @field:Min(0)
    val requestCooldownSeconds: Long = 60,
    @field:Min(1)
    val maxAttempts: Int = 5,
    @field:Pattern(regexp = "^(log|smtp)$")
    val delivery: String = "log",
    @field:NotBlank
    val from: String = "no-reply@mypasture.app",
    @field:Size(min = 32)
    val hmacSecret: String = "local-email-verification-secret-change-me",
)
