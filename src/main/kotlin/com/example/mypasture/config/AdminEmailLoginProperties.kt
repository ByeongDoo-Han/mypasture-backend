package com.example.mypasture.config

import org.springframework.boot.autoconfigure.mail.MailProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/** 관리자 이메일 로그인의 활성화 여부와 독립적인 HMAC 키를 바인딩합니다. */
@ConfigurationProperties("app.auth.admin-email-login")
data class AdminEmailLoginProperties(
    val enabled: Boolean = false,
    val hmacSecret: String = "",
    val from: String = "no-reply@mypasture.app",
) {
    init {
        if (enabled) {
            require(hmacSecret.toByteArray(Charsets.UTF_8).size >= 32 &&
                !hmacSecret.lowercase().contains("local") && !hmacSecret.lowercase().contains("change-me")) {
                "Admin email login requires an independent non-local HMAC secret of at least 32 bytes."
            }
            require(from.contains('@') && !from.contains('\r') && !from.contains('\n')) {
                "Admin email login requires a valid sender address."
            }
        }
    }
}

/** SMTP 자동 설정 활성화와 관계없이 관리자 발송 설정을 안전하게 조회할 수 있게 합니다. */
@Configuration
@EnableConfigurationProperties(MailProperties::class)
class AdminEmailLoginMailConfiguration
