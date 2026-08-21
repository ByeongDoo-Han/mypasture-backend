package com.example.mypasture.auth.infrastructure

import com.example.mypasture.auth.application.EmailVerificationNotificationSender
import com.example.mypasture.auth.application.EmailVerificationRequestedEvent
import com.example.mypasture.config.EmailVerificationProperties
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.event.EventListener
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Component

/** SMTP로 6자리 회원가입 이메일 인증번호를 발송합니다. */
@Component
@ConditionalOnProperty(prefix = "app.auth.email-verification", name = ["delivery"], havingValue = "smtp")
class SmtpEmailVerificationNotificationSender(
    private val mailSender: JavaMailSender,
    private val properties: EmailVerificationProperties,
) : EmailVerificationNotificationSender {
    override fun send(email: String, code: String, expiresAt: java.time.Instant) {
        val message = SimpleMailMessage().apply {
            from = properties.from
            setTo(email)
            subject = "[My Pasture] 이메일 인증번호"
            text = """
                My Pasture 회원가입 인증번호는 $code 입니다.

                이 인증번호는 $expiresAt 까지 사용할 수 있습니다.
                본인이 요청하지 않았다면 이 메일을 무시해 주세요.
            """.trimIndent()
        }
        mailSender.send(message)
    }
}

/** 로컬 개발에서 실제 메일 없이 인증번호를 확인할 수 있게 제한된 개발 로그로 전달합니다. */
@Component
@ConditionalOnProperty(prefix = "app.auth.email-verification", name = ["delivery"], havingValue = "log", matchIfMissing = true)
class LoggingEmailVerificationNotificationSender : EmailVerificationNotificationSender {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun send(email: String, code: String, expiresAt: java.time.Instant) {
        logger.info("DEV ONLY email verification code for emailHash={} expiresAt={} code={}", sha256Hex(email), expiresAt, code)
    }
}

/** 발급 커밋 후 이메일 발송을 코루틴 I/O 실행기로 넘겨 HTTP 응답과 분리합니다. */
@Component
class EmailVerificationEventListener(
    private val sender: EmailVerificationNotificationSender,
    private val coroutineRunner: AuthNotificationCoroutineRunner,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @EventListener
    fun onEmailVerificationRequested(event: EmailVerificationRequestedEvent) {
        coroutineRunner.launch {
            try {
                sender.send(event.email, event.code, event.expiresAt)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: RuntimeException) {
                logger.error("Email verification delivery failed for email hash={}", sha256Hex(event.email), error)
            }
        }
    }
}
