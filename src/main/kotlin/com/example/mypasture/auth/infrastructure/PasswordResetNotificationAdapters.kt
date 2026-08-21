package com.example.mypasture.auth.infrastructure

import com.example.mypasture.auth.application.PasswordResetNotificationSender
import com.example.mypasture.auth.application.PasswordResetRequestedEvent
import com.example.mypasture.config.AuthSecurityProperties
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.web.util.UriComponentsBuilder
import java.time.Instant

/** SMTP를 통해 사용자에게 비밀번호 재설정 링크를 발송하는 adapter입니다. */
@Component
@ConditionalOnProperty(prefix = "app.auth.password-reset", name = ["delivery"], havingValue = "smtp")
class SmtpPasswordResetNotificationSender(
    private val mailSender: JavaMailSender,
    private val properties: AuthSecurityProperties,
) : PasswordResetNotificationSender {
    override fun send(email: String, displayName: String, rawToken: String, expiresAt: Instant) {
        val resetLink = buildResetLink(properties.resetUrl, rawToken)
        val message = SimpleMailMessage().apply {
            from = properties.from
            setTo(email)
            subject = "[My Pasture] 비밀번호 재설정"
            text = """
                ${displayName}님, 비밀번호 재설정 요청이 접수되었습니다.

                아래 링크에서 비밀번호를 변경해 주세요.
                $resetLink

                이 링크는 $expiresAt 까지 한 번만 사용할 수 있습니다.
                본인이 요청하지 않았다면 이 메일을 무시해 주세요.
            """.trimIndent()
        }
        mailSender.send(message)
    }
}

/** 로컬 개발에서 실제 메일 없이 재설정 링크를 확인할 수 있게 로그로 전달하는 adapter입니다. */
@Component
@ConditionalOnProperty(prefix = "app.auth.password-reset", name = ["delivery"], havingValue = "log", matchIfMissing = true)
class LoggingPasswordResetNotificationSender(
    private val properties: AuthSecurityProperties,
) : PasswordResetNotificationSender {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun send(email: String, displayName: String, rawToken: String, expiresAt: Instant) {
        logger.info("DEV ONLY password reset link for {} (expires {}): {}", email, expiresAt, buildResetLink(properties.resetUrl, rawToken))
    }
}

/** DB 커밋이 끝난 뒤 알림 adapter를 호출해 잘못된 토큰이 메일로 발송되는 것을 방지합니다. */
@Component
class PasswordResetEventListener(
    private val sender: PasswordResetNotificationSender,
    private val coroutineRunner: AuthNotificationCoroutineRunner,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onPasswordResetRequested(event: PasswordResetRequestedEvent) {
        coroutineRunner.launch {
            try {
                sender.send(event.email, event.displayName, event.rawToken, event.expiresAt)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: RuntimeException) {
                logger.error("Password reset notification delivery failed for email hash={}", sha256Hex(event.email), error)
            }
        }
    }
}

private fun buildResetLink(baseUrl: String, rawToken: String): String =
    UriComponentsBuilder.fromUriString(baseUrl)
        .queryParam("token", rawToken)
        .build()
        .encode()
        .toUriString()

internal fun sha256Hex(value: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
