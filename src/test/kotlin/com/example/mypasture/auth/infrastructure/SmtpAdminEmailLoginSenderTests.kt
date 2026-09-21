package com.example.mypasture.auth.infrastructure

import com.example.mypasture.auth.application.AdminEmailDeliveryException
import com.example.mypasture.config.AdminEmailLoginProperties
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.boot.autoconfigure.mail.MailProperties
import org.springframework.mail.MailSendException
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSenderImpl
import org.springframework.mock.env.MockEnvironment
import java.time.Instant
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 실제 SMTP 연결 없이 발송 어댑터의 TLS·시간 제한과 민감한 제공자 오류 차단을 검증합니다. */
class SmtpAdminEmailLoginSenderTests {
    private val config = AdminEmailLoginProperties(
        enabled = true,
        hmacSecret = "independent-admin-secret-at-least-thirty-two-bytes",
        from = "no-reply@example.test",
    )
    private val production = MockEnvironment().apply { setActiveProfiles("prod") }

    @Test
    fun `implicit TLS adapter sends through mocked transport with required security settings`() {
        val transportSettings = Properties()
        mockConstruction(JavaMailSenderImpl::class.java) { mock, _ ->
            `when`(mock.javaMailProperties).thenReturn(transportSettings)
        }.use { mocked ->
            val adapter = SmtpAdminEmailLoginSender(config, cloudflareMail(), production)
            adapter.send("admin@example.test", "123456", Instant.now().plusSeconds(300))
            val transport = mocked.constructed().single()
            val message = ArgumentCaptor.forClass(SimpleMailMessage::class.java)
            verify(transport).send(message.capture())
            verify(transport).setHost("smtp.mx.cloudflare.net")
            verify(transport).setPort(465)
            verify(transport).setUsername("api_token")
            assertEquals("no-reply@example.test", message.value.from)
            assertEquals(listOf("admin@example.test"), message.value.to?.toList())
            assertTrue(message.value.text!!.contains("123456"))
            assertEquals("true", transportSettings["mail.smtp.ssl.enable"])
            assertEquals("false", transportSettings["mail.smtp.starttls.enable"])
            assertEquals("true", transportSettings["mail.smtp.ssl.checkserveridentity"])
            assertEquals("false", transportSettings["mail.debug"])
            for (setting in listOf("connectiontimeout", "timeout", "writetimeout")) {
                assertEquals("3000", transportSettings["mail.smtp.$setting"])
            }
        }
    }

    @Test
    fun `SMTP provider failure cannot expose recipient code or credential through exception cause`() {
        mockConstruction(JavaMailSenderImpl::class.java) { mock, _ ->
            `when`(mock.javaMailProperties).thenReturn(Properties())
            doThrow(MailSendException("synthetic provider error admin@example.test code=123456 token=test-token"))
                .`when`(mock).send(any(SimpleMailMessage::class.java) ?: SimpleMailMessage())
        }.use {
            val adapter = SmtpAdminEmailLoginSender(config, cloudflareMail(), production)
            val error = assertFailsWith<AdminEmailDeliveryException> {
                adapter.send("admin@example.test", "123456", Instant.now().plusSeconds(300))
            }
            assertEquals("Admin email delivery failed", error.message)
            assertNull(error.cause)
        }
    }

    private fun cloudflareMail() = MailProperties().apply {
        host = "smtp.mx.cloudflare.net"
        port = 465
        username = "api_token"
        password = "synthetic-test-token"
        properties["mail.smtp.auth"] = "true"
        properties["mail.smtp.ssl.enable"] = "true"
        properties["mail.smtp.starttls.enable"] = "false"
        // 운영 설정이 완화되어 있어도 어댑터가 안전한 값으로 덮어써야 합니다.
        properties["mail.smtp.timeout"] = "0"
        properties["mail.debug"] = "true"
        properties["mail.smtp.ssl.checkserveridentity"] = "false"
    }
}
