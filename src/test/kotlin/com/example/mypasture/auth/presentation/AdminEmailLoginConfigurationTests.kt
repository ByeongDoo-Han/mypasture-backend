package com.example.mypasture.auth.presentation

import com.example.mypasture.auth.application.AdminEmailLoginSender
import com.example.mypasture.auth.application.AdminEmailLoginService
import com.example.mypasture.auth.application.AuthService
import com.example.mypasture.auth.application.InvalidAdminEmailCodeException
import com.example.mypasture.auth.infrastructure.AdminEmailLoginStateRepository
import com.example.mypasture.auth.infrastructure.SmtpAdminEmailLoginSender
import com.example.mypasture.auth.infrastructure.UserRepository
import com.example.mypasture.auth.presentation.dto.AdminEmailLoginConfirmRequest
import com.example.mypasture.auth.presentation.dto.AdminEmailLoginRequest
import com.example.mypasture.config.AdminEmailLoginProperties
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.boot.autoconfigure.mail.MailProperties
import org.springframework.mock.env.MockEnvironment
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 명시적 활성화와 강한 키·SMTP 설정 없이는 관리자 이메일 인증이 동작하지 않음을 검증합니다. */
class AdminEmailLoginConfigurationTests {
    private val strongKey = "independent-admin-secret-at-least-thirty-two-bytes"

    @Test
    fun `disabled feature returns generic request and denies confirmation without touching dependencies`() {
        val users = mock(UserRepository::class.java)
        val states = mock(AdminEmailLoginStateRepository::class.java)
        val sender = mock(AdminEmailLoginSender::class.java)
        val auth = mock(AuthService::class.java)
        val manager = mock(EntityManager::class.java)
        val service = AdminEmailLoginService(users, states, sender, AdminEmailLoginProperties(), auth, manager)
        assertEquals(300, service.request(AdminEmailLoginRequest("admin@example.test")).expiresInSeconds)
        assertFailsWith<InvalidAdminEmailCodeException> {
            service.confirm(AdminEmailLoginConfirmRequest(UUID.randomUUID(), "123456"))
        }
        verifyNoInteractions(users, states, sender, auth, manager)
    }

    @Test
    fun `enabled feature rejects missing short and local placeholder keys`() {
        for (key in listOf("", "short", "local-secret-not-for-production-12345678", "change-me-123456789012345678901234567890")) {
            assertFailsWith<IllegalArgumentException> { AdminEmailLoginProperties(enabled = true, hmacSecret = key) }
        }
        AdminEmailLoginProperties(enabled = true, hmacSecret = strongKey)
    }

    @Test
    fun `SMTP is mandatory when enabled and production requires credentials plus transport encryption`() {
        val properties = AdminEmailLoginProperties(enabled = true, hmacSecret = strongKey)
        assertFailsWith<IllegalArgumentException> { SmtpAdminEmailLoginSender(properties, MailProperties(), MockEnvironment()) }
        val mail = MailProperties().apply { host = "smtp.example.test" }
        val production = MockEnvironment().withProperty("spring.profiles.active", "prod").apply { setActiveProfiles("prod") }
        assertFailsWith<IllegalArgumentException> { SmtpAdminEmailLoginSender(properties, mail, production) }
        mail.username = "test-user"
        mail.password = "test-password"
        assertFailsWith<IllegalArgumentException> { SmtpAdminEmailLoginSender(properties, mail, production) }
        mail.properties["mail.smtp.starttls.enable"] = "true"
        SmtpAdminEmailLoginSender(properties, mail, production)
    }

    @Test
    fun `production accepts implicit TLS without STARTTLS and rejects explicit plaintext transport`() {
        val properties = AdminEmailLoginProperties(enabled = true, hmacSecret = strongKey)
        val mail = MailProperties().apply {
            host = "smtp.mx.cloudflare.net"
            port = 465
            username = "api_token"
            password = "synthetic-test-token"
            this.properties["mail.smtp.starttls.enable"] = "false"
            this.properties["mail.smtp.ssl.enable"] = "true"
        }
        val production = MockEnvironment().apply { setActiveProfiles("prod") }
        // 생성 시 네트워크 연결은 없으며 발송은 별도 transport mock 시험으로 검증합니다.
        SmtpAdminEmailLoginSender(properties, mail, production)
        mail.properties["mail.smtp.ssl.enable"] = "false"
        assertFailsWith<IllegalArgumentException> { SmtpAdminEmailLoginSender(properties, mail, production) }
    }
}
