package com.example.mypasture.auth.presentation

import com.example.mypasture.auth.application.EmailVerificationNotificationSender
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 이메일 인증번호 발급부터 일회성 증명을 소비하는 회원가입까지 검증합니다. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "app.auth.email-verification.required=true",
        "app.auth.email-verification.request-cooldown-seconds=0",
        "app.auth.email-verification.max-attempts=5",
    ],
)
class EmailVerificationControllerTests(
    @Autowired private val mockMvc: MockMvc,
) {
    @MockitoBean
    private lateinit var emailSender: EmailVerificationNotificationSender

    @Test
    fun `verified email token creates account once`() {
        val email = "verified-${UUID.randomUUID()}@example.com"
        val code = requestCode(email)
        val verificationToken = confirm(email, code)

        mockMvc.post("/api/v1/auth/signup") {
            contentType = MediaType.APPLICATION_JSON
            content =
                """
                {
                  "email": "$email",
                  "password": "password123!",
                  "displayName": "Verified",
                  "verificationToken": "$verificationToken"
                }
                """.trimIndent()
        }.andExpect {
            status { isCreated() }
            jsonPath("$.user.email") { value(email) }
        }
    }

    @Test
    fun `signup without verified email token is rejected`() {
        mockMvc.post("/api/v1/auth/signup") {
            contentType = MediaType.APPLICATION_JSON
            content =
                """{"email":"missing-${UUID.randomUUID()}@example.com","password":"password123!","displayName":"Missing"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("EMAIL_VERIFICATION_REQUIRED") }
        }
    }

    @Test
    fun `too many wrong codes invalidates the current code`() {
        val email = "attempts-${UUID.randomUUID()}@example.com"
        val actualCode = requestCode(email)
        val wrongCode = if (actualCode == "999999") "888888" else "999999"

        repeat(5) {
            mockMvc.post("/api/v1/auth/email-verifications/confirm") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"email":"$email","code":"$wrongCode"}"""
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("INVALID_EMAIL_VERIFICATION_CODE") }
            }
        }

        mockMvc.post("/api/v1/auth/email-verifications/confirm") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","code":"$actualCode"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_EMAIL_VERIFICATION_CODE") }
        }
    }

    private fun requestCode(email: String): String {
        val code = AtomicReference<String>()
        val delivered = CountDownLatch(1)
        doAnswer { invocation ->
            code.set(invocation.getArgument(1))
            delivered.countDown()
            null
        }.`when`(emailSender).send(anyString(), anyString(), anyInstant())

        mockMvc.post("/api/v1/auth/email-verifications") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email"}"""
        }.andExpect {
            status { isAccepted() }
            jsonPath("$.expiresInSeconds") { value(600) }
            jsonPath("$.resendAfterSeconds") { value(0) }
        }

        check(delivered.await(2, TimeUnit.SECONDS)) { "인증 메일이 제한 시간 안에 전달되지 않았습니다." }
        return requireNotNull(code.get())
    }

    private fun confirm(email: String, code: String): String {
        val result = mockMvc.post("/api/v1/auth/email-verifications/confirm") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","code":"$code"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.verificationToken") { exists() }
            jsonPath("$.expiresAt") { exists() }
        }.andReturn()
        return com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(result.response.contentAsString)["verificationToken"]
            .asText()
            .also(::assertNotNull)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyInstant(): T {
        org.mockito.ArgumentMatchers.any<T>()
        return null as T
    }
}
