package com.example.mypasture.auth.presentation

import com.example.mypasture.auth.application.SocialLoginCommand
import com.example.mypasture.auth.application.SocialUserProfile
import com.example.mypasture.auth.domain.AuthProvider
import com.example.mypasture.auth.application.PasswordResetNotificationSender
import com.example.mypasture.auth.infrastructure.AppleOidcOAuthClient
import com.example.mypasture.auth.infrastructure.GoogleOidcOAuthClient
import com.example.mypasture.auth.infrastructure.KakaoOAuthClient
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.BDDMockito.given
import org.mockito.Mockito.doAnswer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.TestPropertySource
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/** AuthController의 회원가입 HTTP 흐름을 검증하는 통합 테스트입니다. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = ["app.security.jwt.access-token-ttl-seconds=900"])
class AuthControllerTests(
    @Autowired private val mockMvc: MockMvc,
) {
    @MockitoBean
    private lateinit var googleOidcOAuthClient: GoogleOidcOAuthClient

    @MockitoBean
    private lateinit var appleOidcOAuthClient: AppleOidcOAuthClient

    @MockitoBean
    private lateinit var kakaoOAuthClient: KakaoOAuthClient

    @MockitoBean
    private lateinit var passwordResetNotificationSender: PasswordResetNotificationSender

    @BeforeEach
    fun configureSocialProviders() {
        given(googleOidcOAuthClient.provider).willReturn(AuthProvider.GOOGLE)
        given(appleOidcOAuthClient.provider).willReturn(AuthProvider.APPLE)
        given(kakaoOAuthClient.provider).willReturn(AuthProvider.KAKAO)
    }

    @Test
    fun `signup creates email user`() {
        mockMvc.post("/api/v1/auth/signup") {
            contentType = MediaType.APPLICATION_JSON
            content =
                """
                {
                  "email": "signup-success@example.com",
                  "password": "password123!",
                  "displayName": "Signup User"
                }
                """.trimIndent()
        }.andExpect {
            status { isCreated() }
            jsonPath("$.user.id") { exists() }
            jsonPath("$.user.email") { value("signup-success@example.com") }
            jsonPath("$.user.displayName") { value("Signup User") }
            jsonPath("$.user.subscriptionTier") { value("FREE") }
        }
    }

    @Test
    fun `signup rejects duplicate email`() {
        val payload =
            """
            {
              "email": "duplicate@example.com",
              "password": "password123!",
              "displayName": "Duplicate User"
            }
            """.trimIndent()

        mockMvc.post("/api/v1/auth/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = payload
        }.andExpect {
            status { isCreated() }
        }

        mockMvc.post("/api/v1/auth/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = payload
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("EMAIL_ALREADY_REGISTERED") }
        }
    }

    @Test
    fun `login returns jwt access token and me returns current user`() {
        val email = "jwt-login@example.com"
        val password = "password123!"
        mockMvc.post("/api/v1/auth/signup") {
            contentType = MediaType.APPLICATION_JSON
            content =
                """
                {
                  "email": "$email",
                  "password": "$password",
                  "displayName": "JWT User"
                }
                """.trimIndent()
        }.andExpect {
            status { isCreated() }
        }

        val loginResult = mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content =
                """
                {
                  "email": "$email",
                  "password": "$password"
                }
                """.trimIndent()
        }.andExpect {
            status { isOk() }
            jsonPath("$.token") { exists() }
            jsonPath("$.tokenType") { value("Bearer") }
            jsonPath("$.expiresIn") { value(900) }
            jsonPath("$.user.email") { value(email) }
        }.andReturn()

        val token = com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(loginResult.response.contentAsString)["token"]
            .asText()

        mockMvc.get("/api/v1/auth/me") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.email") { value(email) }
            jsonPath("$.displayName") { value("JWT User") }
        }
    }


    @Test
    fun `social login creates google user and returns jwt`() {
        val challenge = createSocialChallenge()
        given(googleOidcOAuthClient.fetchProfile(SocialLoginCommand(AuthProvider.GOOGLE, "valid-google-id-token", null, null))).willReturn(
            SocialUserProfile(
                provider = AuthProvider.GOOGLE,
                providerUserId = "google-sub-001",
                email = "google-user@example.com",
                emailVerified = true,
                displayName = "Google User",
                nonce = challenge["nonce"].asText(),
            ),
        )

        val loginResult = mockMvc.post("/api/v1/auth/social-login") {
            contentType = MediaType.APPLICATION_JSON
            content =
                """
                {
                  "provider": "GOOGLE",
                  "idToken": "valid-google-id-token",
                  "challengeId": "${challenge["challengeId"].asText()}"
                }
                """.trimIndent()
        }.andExpect {
            status { isOk() }
            jsonPath("$.token") { exists() }
            jsonPath("$.tokenType") { value("Bearer") }
            jsonPath("$.user.email") { value("google-user@example.com") }
            jsonPath("$.user.displayName") { value("Google User") }
        }.andReturn()

        val token = com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(loginResult.response.contentAsString)["token"]
            .asText()

        mockMvc.get("/api/v1/auth/me") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.email") { value("google-user@example.com") }
        }
    }

    @Test
    fun `social login creates apple user and returns jwt`() {
        val challenge = createSocialChallenge()
        given(appleOidcOAuthClient.fetchProfile(SocialLoginCommand(AuthProvider.APPLE, "valid-apple-id-token", null, "Apple User"))).willReturn(
            SocialUserProfile(
                provider = AuthProvider.APPLE,
                providerUserId = "apple-sub-001",
                email = "apple-user@privaterelay.appleid.com",
                emailVerified = true,
                displayName = "Apple User",
                nonce = challenge["nonce"].asText(),
            ),
        )

        mockMvc.post("/api/v1/auth/social-login") {
            contentType = MediaType.APPLICATION_JSON
            content =
                """
                {
                  "provider": "APPLE",
                  "idToken": "valid-apple-id-token",
                  "displayName": "Apple User",
                  "challengeId": "${challenge["challengeId"].asText()}"
                }
                """.trimIndent()
        }.andExpect {
            status { isOk() }
            jsonPath("$.token") { exists() }
            jsonPath("$.tokenType") { value("Bearer") }
            jsonPath("$.user.email") { value("apple-user@privaterelay.appleid.com") }
        }
    }

    @Test
    fun `social login creates kakao user and returns jwt`() {
        given(kakaoOAuthClient.fetchProfile(SocialLoginCommand(AuthProvider.KAKAO, null, "valid-kakao-access-token", "Kakao User"))).willReturn(
            SocialUserProfile(
                provider = AuthProvider.KAKAO,
                providerUserId = "kakao-sub-001",
                email = "kakao-user@example.com",
                emailVerified = true,
                displayName = "Kakao User",
            ),
        )

        mockMvc.post("/api/v1/auth/social-login") {
            contentType = MediaType.APPLICATION_JSON
            content =
                """
                {
                  "provider": "KAKAO",
                  "accessToken": "valid-kakao-access-token",
                  "displayName": "Kakao User"
                }
                """.trimIndent()
        }.andExpect {
            status { isOk() }
            jsonPath("$.token") { exists() }
            jsonPath("$.tokenType") { value("Bearer") }
            jsonPath("$.user.email") { value("kakao-user@example.com") }
        }
    }

    @Test
    fun `social login rejects email provider`() {
        mockMvc.post("/api/v1/auth/social-login") {
            contentType = MediaType.APPLICATION_JSON
            content =
                """
                {
                  "provider": "EMAIL",
                  "idToken": "not-social"
                }
                """.trimIndent()
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("UNSUPPORTED_SOCIAL_PROVIDER") }
        }
    }

    private fun createSocialChallenge(): com.fasterxml.jackson.databind.JsonNode {
        val result = mockMvc.post("/api/v1/auth/social-login/challenges")
            .andExpect {
                status { isOk() }
                jsonPath("$.challengeId") { exists() }
                jsonPath("$.nonce") { exists() }
            }
            .andReturn()
        return com.fasterxml.jackson.databind.ObjectMapper().readTree(result.response.contentAsString)
    }

    @Test
    fun `me rejects request without bearer token`() {
        mockMvc.get("/api/v1/auth/me")
            .andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value("UNAUTHORIZED") }
            }
    }

    @Test
    fun `update me normalizes display name and keeps current token valid`() {
        val email = "profile-${java.util.UUID.randomUUID()}@example.com"
        val token = signupAndLoginWithDisplayName(email, "Profile User")

        mockMvc.patch("/api/v1/auth/me") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"displayName":"  새   이름  "}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.email") { value(email) }
            jsonPath("$.displayName") { value("새 이름") }
        }

        mockMvc.get("/api/v1/auth/me") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.displayName") { value("새 이름") }
        }
    }

    @Test
    fun `update me validates authentication and display name`() {
        mockMvc.patch("/api/v1/auth/me") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"displayName":"새 이름"}"""
        }.andExpect { status { isUnauthorized() } }

        val token = signupAndLoginWithDisplayName("profile-validation-${java.util.UUID.randomUUID()}@example.com", "Profile User")
        mockMvc.patch("/api/v1/auth/me") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"displayName":"${"가".repeat(41)}"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }
    }

    private fun signupAndLoginWithDisplayName(email: String, displayName: String): String {
        val password = "password123!"
        mockMvc.post("/api/v1/auth/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"$password","displayName":"$displayName"}"""
        }.andExpect { status { isCreated() } }
        val login = mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"$password"}"""
        }.andExpect { status { isOk() } }.andReturn()
        return com.fasterxml.jackson.databind.ObjectMapper().readTree(login.response.contentAsString)["token"].asText()
    }

    @Test
    fun `refresh token rotates once and reuse revokes the token family`() {
        val email = "refresh-${java.util.UUID.randomUUID()}@example.com"
        val password = "password123!"
        mockMvc.post("/api/v1/auth/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"$password","displayName":"Refresh User"}"""
        }.andExpect { status { isCreated() } }
        val login = mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"$password"}"""
        }.andReturn()
        val loginBody = com.fasterxml.jackson.databind.ObjectMapper().readTree(login.response.contentAsString)
        val originalRefreshToken = loginBody["refreshToken"].asText()

        val refresh = mockMvc.post("/api/v1/auth/refresh") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"refreshToken":"$originalRefreshToken"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.refreshToken") { exists() }
        }.andReturn()
        val rotatedAccessToken = com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(refresh.response.contentAsString)["token"].asText()

        mockMvc.post("/api/v1/auth/refresh") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"refreshToken":"$originalRefreshToken"}"""
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("INVALID_REFRESH_TOKEN") }
        }
        mockMvc.get("/api/v1/auth/me") {
            header("Authorization", "Bearer $rotatedAccessToken")
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `logout revokes access and refresh sessions`() {
        val email = "logout-${java.util.UUID.randomUUID()}@example.com"
        val password = "password123!"
        mockMvc.post("/api/v1/auth/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"$password","displayName":"Logout User"}"""
        }.andExpect { status { isCreated() } }
        val login = mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"$password"}"""
        }.andReturn()
        val body = com.fasterxml.jackson.databind.ObjectMapper().readTree(login.response.contentAsString)
        val accessToken = body["token"].asText()
        val refreshToken = body["refreshToken"].asText()

        mockMvc.post("/api/v1/auth/logout") {
            header("Authorization", "Bearer $accessToken")
        }.andExpect { status { isNoContent() } }
        mockMvc.get("/api/v1/auth/me") {
            header("Authorization", "Bearer $accessToken")
        }.andExpect { status { isUnauthorized() } }
        mockMvc.post("/api/v1/auth/refresh") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"refreshToken":"$refreshToken"}"""
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `password reset changes password invalidates sessions and consumes token once`() {
        val email = "password-reset@example.com"
        val oldPassword = "old-password123!"
        val newPassword = "new-password456!"
        val oldToken = signupAndLogin(email, oldPassword)
        val deliveredToken = AtomicReference<String>()

        doAnswer { invocation ->
            deliveredToken.set(invocation.getArgument(2))
            null
        }.`when`(passwordResetNotificationSender).send(
            anyString(),
            anyString(),
            anyString(),
            anyValue<Instant>(),
        )

        mockMvc.post("/api/v1/auth/password-reset/requests") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email"}"""
        }.andExpect {
            status { isAccepted() }
            jsonPath("$.message") { exists() }
        }

        val resetToken = awaitValue(deliveredToken)
        mockMvc.post("/api/v1/auth/password-reset/confirm") {
            contentType = MediaType.APPLICATION_JSON
            content =
                """
                {
                  "token": "$resetToken",
                  "newPassword": "$newPassword"
                }
                """.trimIndent()
        }.andExpect {
            status { isNoContent() }
        }

        mockMvc.get("/api/v1/auth/me") {
            header("Authorization", "Bearer $oldToken")
        }.andExpect {
            status { isUnauthorized() }
        }

        mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"$oldPassword"}"""
        }.andExpect {
            status { isUnauthorized() }
        }

        mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"$newPassword"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.token") { exists() }
        }

        mockMvc.post("/api/v1/auth/password-reset/confirm") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"token":"$resetToken","newPassword":"another-password789!"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_PASSWORD_RESET_TOKEN") }
        }
    }

    @Test
    fun `password reset request does not reveal whether account exists`() {
        mockMvc.post("/api/v1/auth/password-reset/requests") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"unknown-account@example.com"}"""
        }.andExpect {
            status { isAccepted() }
            jsonPath("$.message") { value("가입된 이메일이라면 비밀번호 재설정 안내를 발송했습니다.") }
        }
    }

    @Test
    fun `delete account anonymizes user invalidates token and allows signup again`() {
        val email = "withdraw@example.com"
        val password = "password123!"
        val token = signupAndLogin(email, password)

        mockMvc.delete("/api/v1/auth/me") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content =
                """
                {
                  "confirmation": "DELETE",
                  "currentPassword": "$password"
                }
                """.trimIndent()
        }.andExpect {
            status { isNoContent() }
        }

        mockMvc.get("/api/v1/auth/me") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isUnauthorized() }
        }

        mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"$password"}"""
        }.andExpect {
            status { isUnauthorized() }
        }

        mockMvc.post("/api/v1/auth/signup") {
            contentType = MediaType.APPLICATION_JSON
            content =
                """
                {
                  "email": "$email",
                  "password": "$password",
                  "displayName": "Rejoined User"
                }
                """.trimIndent()
        }.andExpect {
            status { isCreated() }
            jsonPath("$.user.displayName") { value("Rejoined User") }
        }
    }

    @Test
    fun `delete account requires current password for email account`() {
        val email = "withdraw-reauth@example.com"
        val password = "password123!"
        val token = signupAndLogin(email, password)

        mockMvc.delete("/api/v1/auth/me") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content =
                """
                {
                  "confirmation": "DELETE",
                  "currentPassword": "wrong-password!"
                }
                """.trimIndent()
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("REAUTHENTICATION_FAILED") }
        }

        mockMvc.get("/api/v1/auth/me") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyValue(): T {
        any<T>()
        return null as T
    }

    private fun signupAndLogin(email: String, password: String): String {
        mockMvc.post("/api/v1/auth/signup") {
            contentType = MediaType.APPLICATION_JSON
            content =
                """
                {
                  "email": "$email",
                  "password": "$password",
                  "displayName": "Auth Test User"
                }
                """.trimIndent()
        }.andExpect {
            status { isCreated() }
        }

        val result = mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"$password"}"""
        }.andExpect {
            status { isOk() }
        }.andReturn()

        return com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(result.response.contentAsString)["token"]
            .asText()
    }

    private fun <T> awaitValue(reference: AtomicReference<T>, timeoutMillis: Long = 2_000): T {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            reference.get()?.let { return it }
            Thread.sleep(10)
        }
        return requireNotNull(reference.get()) { "비동기 알림이 제한 시간 안에 전달되지 않았습니다." }
    }

}
