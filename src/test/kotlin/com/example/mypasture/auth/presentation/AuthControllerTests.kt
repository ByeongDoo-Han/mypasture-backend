package com.example.mypasture.auth.presentation

import com.example.mypasture.auth.domain.uniqueTestNickname

import com.example.mypasture.ai.application.AiCachePort
import com.example.mypasture.auth.application.PasswordResetNotificationSender
import com.example.mypasture.auth.application.SocialLoginCommand
import com.example.mypasture.auth.application.SocialUserProfile
import com.example.mypasture.auth.domain.AuthProvider
import com.example.mypasture.auth.infrastructure.AppleOidcOAuthClient
import com.example.mypasture.auth.infrastructure.GoogleOidcOAuthClient
import com.example.mypasture.auth.infrastructure.KakaoOAuthClient
import com.example.mypasture.auth.infrastructure.KakaoWebCodeExchangeClient
import com.example.mypasture.auth.infrastructure.InvalidSocialTokenException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.BDDMockito.given
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AuthController의 회원가입 HTTP 흐름을 검증하는 통합 테스트입니다. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "app.security.jwt.access-token-ttl-seconds=900",
        "app.oauth.google.web-client-ids=google-review-client.apps.googleusercontent.com",
        "app.oauth.apple.client-ids=com.mypasture.app",
        "app.oauth.kakao.app-ids=123456789",
        "app.oauth.kakao.web.rest-api-key=test-rest-key",
        "app.oauth.kakao.web.client-secret=test-client-secret",
        "app.oauth.kakao.web.redirect-uri=https://app.mypasture.app/oauth/kakao",
    ],
)
class AuthControllerTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {
    @Test
    fun `웹 세션 쿠키는 로그인을 복원하고 로그아웃 시 제거된다`() {
        val email = "web-session-${UUID.randomUUID()}@example.com"
        mockMvc.post("/api/v1/auth/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"password123!","displayName":"WebSession"}"""
        }.andExpect { status { isCreated() } }
        val login = mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"password123!"}"""
        }.andExpect { status { isOk() } }.andReturn()
        val originalRefresh = com.fasterxml.jackson.databind.ObjectMapper().readTree(login.response.contentAsString)["refreshToken"].asText()

        val created = mockMvc.post("/api/v1/auth/web/session") {
            header("Origin", "http://localhost:8084")
            contentType = MediaType.APPLICATION_JSON
            content = """{"refreshToken":"$originalRefresh"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.refreshToken") { doesNotExist() }
            jsonPath("$.user.email") { value(email) }
            header { string("Cache-Control", "no-store") }
        }.andReturn()
        val firstCookie = created.response.getHeader("Set-Cookie")!!
        assertTrue(firstCookie.startsWith("__Host-mypasture-web-refresh="))
        assertTrue(firstCookie.contains("HttpOnly"))
        assertTrue(firstCookie.contains("Secure"))
        assertTrue(firstCookie.contains("SameSite=Strict"))
        assertTrue(firstCookie.contains("Path=/"))
        val cookieValue = firstCookie.substringAfter('=').substringBefore(';')

        val restored = mockMvc.post("/api/v1/auth/web/refresh") {
            header("Origin", "http://localhost:8084")
            cookie(jakarta.servlet.http.Cookie("__Host-mypasture-web-refresh", cookieValue))
        }.andExpect {
            status { isOk() }
            jsonPath("$.refreshToken") { doesNotExist() }
            jsonPath("$.user.email") { value(email) }
        }.andReturn()
        val rotatedCookie = restored.response.getHeader("Set-Cookie")!!.substringAfter('=').substringBefore(';')
        assertTrue(rotatedCookie != cookieValue)

        mockMvc.post("/api/v1/auth/web/logout") {
            header("Origin", "http://localhost:8084")
            cookie(jakarta.servlet.http.Cookie("__Host-mypasture-web-refresh", rotatedCookie))
        }.andExpect {
            status { isNoContent() }
            header { string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")) }
        }
        mockMvc.post("/api/v1/auth/web/refresh") {
            header("Origin", "http://localhost:8084")
            cookie(jakarta.servlet.http.Cookie("__Host-mypasture-web-refresh", rotatedCookie))
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `신뢰하지 않는 출처 출처 누락 쿠키 누락은 웹 세션에서 거부된다`() {
        for (origin in listOf(null, "https://attacker.example")) {
            mockMvc.post("/api/v1/auth/web/refresh") {
                if (origin != null) header("Origin", origin)
            }.andExpect { status { isForbidden() } }
        }
        mockMvc.post("/api/v1/auth/web/refresh") {
            header("Origin", "http://localhost:8084")
        }.andExpect {
            status { isUnauthorized() }
            header { string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")) }
        }
        mockMvc.post("/api/v1/auth/web/refresh") {
            header("Origin", "http://localhost:8084")
            cookie(jakarta.servlet.http.Cookie("__Host-mypasture-web-refresh", "x".repeat(1001)))
        }.andExpect { status { isUnauthorized() } }
    }
    @MockitoBean
    private lateinit var googleOidcOAuthClient: GoogleOidcOAuthClient

    @MockitoBean
    private lateinit var appleOidcOAuthClient: AppleOidcOAuthClient

    @MockitoBean
    private lateinit var kakaoOAuthClient: KakaoOAuthClient

    @MockitoBean
    private lateinit var kakaoWebCodeExchangeClient: KakaoWebCodeExchangeClient

    @MockitoBean
    private lateinit var passwordResetNotificationSender: PasswordResetNotificationSender

    @MockitoBean
    private lateinit var aiCachePort: AiCachePort

    @BeforeEach
    fun configureSocialProviders() {
        given(googleOidcOAuthClient.provider).willReturn(AuthProvider.GOOGLE)
        given(appleOidcOAuthClient.provider).willReturn(AuthProvider.APPLE)
        given(kakaoOAuthClient.provider).willReturn(AuthProvider.KAKAO)
    }

    @Test
    fun `소셜 제공자 준비 상태는 공개하되 클라이언트 ID는 노출하지 않는다`() {
        mockMvc.get("/api/v1/auth/social-login/providers")
            .andExpect {
                status { isOk() }
                jsonPath("$.providers.GOOGLE") { value(true) }
                jsonPath("$.providers.APPLE") { value(true) }
                jsonPath("$.providers.KAKAO") { value(true) }
                jsonPath("$.providers.KAKAO_WEB") { value(true) }
                jsonPath("$.providers.clientIds") { doesNotExist() }
            }
    }

    @Test
    fun `회원가입은 이메일 사용자를 만든다`() {
        mockMvc.post("/api/v1/auth/signup") {
            contentType = MediaType.APPLICATION_JSON
            content =
                """
                {
                  "email": "signup-success@example.com",
                  "password": "password123!",
                  "displayName": "SignupUser"
                }
                """.trimIndent()
        }.andExpect {
            status { isCreated() }
            jsonPath("$.user.id") { exists() }
            jsonPath("$.user.email") { value("signup-success@example.com") }
            jsonPath("$.user.displayName") { value("SignupUser") }
            jsonPath("$.user.subscriptionTier") { value("FREE") }
        }
    }

    @Test
    fun `회원가입은 중복 이메일을 거부한다`() {
        val payload =
            """
            {
              "email": "duplicate@example.com",
              "password": "password123!",
              "displayName": "Duplicate"
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
    fun `닉네임 중복 검사는 잘못되거나 이미 사용 중인 이름을 거부하고 가입 때 다시 검증한다`() {
        mockMvc.get("/api/v1/auth/nicknames/availability") { param("nickname", "중복검사") }
            .andExpect { status { isOk() }; jsonPath("$.available") { value(true) } }
        mockMvc.get("/api/v1/auth/nicknames/availability") { param("nickname", "띄어 쓰기") }
            .andExpect { status { isBadRequest() }; jsonPath("$.code") { value("INVALID_DISPLAY_NAME") } }

        val first = """{"email":"nickname-first@example.com","password":"password123!","displayName":"중복검사"}"""
        mockMvc.post("/api/v1/auth/signup") { contentType = MediaType.APPLICATION_JSON; content = first }
            .andExpect { status { isCreated() } }
        mockMvc.get("/api/v1/auth/nicknames/availability") { param("nickname", "중복검사") }
            .andExpect { status { isOk() }; jsonPath("$.available") { value(false) } }
        val second = """{"email":"nickname-second@example.com","password":"password123!","displayName":"중복검사"}"""
        mockMvc.post("/api/v1/auth/signup") { contentType = MediaType.APPLICATION_JSON; content = second }
            .andExpect { status { isConflict() }; jsonPath("$.code") { value("NICKNAME_ALREADY_USED") } }
    }

    @Test
    fun `닉네임 중복 검사는 이모지 기호 및 한글 영어 길이 제한 초과를 거부한다`() {
        listOf("가 나", "사랑🙂", "안녕!", "한글123", "가".repeat(9), "a".repeat(11)).forEach { nickname ->
            mockMvc.get("/api/v1/auth/nicknames/availability") { param("nickname", nickname) }
                .andExpect { status { isBadRequest() }; jsonPath("$.code") { value("INVALID_DISPLAY_NAME") } }
        }
    }

    @Test
    fun `로그인은 JWT 액세스 토큰을 반환하고 내 정보는 현재 사용자를 반환한다`() {
        val email = "jwt-login@example.com"
        val password = "password123!"
        mockMvc.post("/api/v1/auth/signup") {
            contentType = MediaType.APPLICATION_JSON
            content =
                """
                {
                  "email": "$email",
                  "password": "$password",
                  "displayName": "JWTUser"
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
            jsonPath("$.displayName") { value("JWTUser") }
        }
    }


    @Test
    fun `구글 소셜 로그인은 사용자를 만들고 JWT를 반환한다`() {
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
            jsonPath("$.user.displayName") { exists() }
        }.andReturn()

        val generatedNickname = com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(loginResult.response.contentAsString)["user"]["displayName"].asText()
        assertTrue(generatedNickname.length in 5..6)
        assertTrue(generatedNickname.all { it in '가'..'힣' })
        assertTrue(generatedNickname != "Google User")

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
    fun `애플 소셜 로그인은 사용자를 만들고 JWT를 반환한다`() {
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
    fun `카카오 소셜 로그인은 사용자를 만들고 JWT를 반환한다`() {
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
    fun `웹 카카오 인가 코드 교환은 일반 소셜 로그인과 같은 세션을 발급한다`() {
        val verifier = "A".repeat(43)
        given(kakaoWebCodeExchangeClient.exchange("single-use-code", verifier)).willReturn("web-kakao-access-token")
        given(kakaoOAuthClient.fetchProfile(SocialLoginCommand(AuthProvider.KAKAO, null, "web-kakao-access-token", null))).willReturn(
            SocialUserProfile(
                provider = AuthProvider.KAKAO,
                providerUserId = "web-kakao-sub-001",
                email = "web-kakao-user@example.com",
                emailVerified = true,
                displayName = "Web Kakao User",
            ),
        )

        mockMvc.post("/api/v1/auth/social-login/kakao/web") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"code":"single-use-code","codeVerifier":"$verifier"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.tokenType") { value("Bearer") }
            jsonPath("$.token") { exists() }
            jsonPath("$.user.email") { value("web-kakao-user@example.com") }
            jsonPath("$.accessToken") { doesNotExist() }
        }
        verify(kakaoWebCodeExchangeClient).exchange("single-use-code", verifier)
    }

    @Test
    fun `웹 카카오 로그인은 잘못된 코드를 돌려주지 않고 거부한다`() {
        val verifier = "A".repeat(43)
        given(kakaoWebCodeExchangeClient.exchange("invalid-code", verifier))
            .willThrow(InvalidSocialTokenException("Kakao authorization code is invalid"))

        val result = mockMvc.post("/api/v1/auth/social-login/kakao/web") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"code":"invalid-code","codeVerifier":"$verifier"}"""
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("INVALID_SOCIAL_TOKEN") }
        }.andReturn()
        assertTrue(!result.response.contentAsString.contains("invalid-code"))
    }

    @Test
    fun `웹 카카오 로그인은 인가 코드와 PKCE 검증값을 검사한다`() {
        for (body in listOf(
            """{"code":"","codeVerifier":"${"A".repeat(43)}"}""",
            """{"code":"valid-code","codeVerifier":"short"}""",
        )) {
            mockMvc.post("/api/v1/auth/social-login/kakao/web") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect { status { isBadRequest() } }
        }
    }

    @Test
    fun `소셜 로그인은 이메일 제공자를 거부한다`() {
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
    fun `내 정보 API는 Bearer 토큰이 없으면 거부한다`() {
        mockMvc.get("/api/v1/auth/me")
            .andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value("UNAUTHORIZED") }
            }
    }

    @Test
    fun `내 정보 수정은 검증한 닉네임을 저장하고 기존 토큰을 유지한다`() {
        val email = "profile-${java.util.UUID.randomUUID()}@example.com"
        val token = signupAndLoginWithDisplayName(email, "ProfileOne")

        mockMvc.get("/api/v1/auth/me/nickname-availability") {
            header("Authorization", "Bearer $token")
            param("nickname", "ProfileOne")
        }.andExpect { status { isOk() }; jsonPath("$.available") { value(true) } }

        mockMvc.get("/api/v1/auth/me/nickname-availability") { param("nickname", "새이름") }
            .andExpect { status { isUnauthorized() } }

        mockMvc.patch("/api/v1/auth/me") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"displayName":"새이름"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.email") { value(email) }
            jsonPath("$.displayName") { value("새이름") }
        }

        mockMvc.get("/api/v1/auth/me") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.displayName") { value("새이름") }
        }
    }

    @Test
    fun `내 정보 수정은 인증과 표시 이름을 검증한다`() {
        mockMvc.patch("/api/v1/auth/me") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"displayName":"새 이름"}"""
        }.andExpect { status { isUnauthorized() } }

        val token = signupAndLoginWithDisplayName("profile-validation-${java.util.UUID.randomUUID()}@example.com", "ProfileTwo")
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
    fun `갱신 토큰은 한 번만 교체되고 재사용 시 토큰 계열을 폐기한다`() {
        val email = "refresh-${java.util.UUID.randomUUID()}@example.com"
        val password = "password123!"
        mockMvc.post("/api/v1/auth/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"$password","displayName":"RefreshOne"}"""
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
    fun `로그아웃은 액세스 및 갱신 세션을 폐기한다`() {
        val email = "logout-${java.util.UUID.randomUUID()}@example.com"
        val password = "password123!"
        mockMvc.post("/api/v1/auth/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"$password","displayName":"LogoutOne"}"""
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
    fun `비밀번호 재설정은 비밀번호를 바꾸고 세션을 무효화하며 토큰을 한 번만 소비한다`() {
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
    fun `비밀번호 재설정 요청은 계정 존재 여부를 드러내지 않는다`() {
        mockMvc.post("/api/v1/auth/password-reset/requests") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"unknown-account@example.com"}"""
        }.andExpect {
            status { isAccepted() }
            jsonPath("$.message") { value("가입된 이메일이라면 비밀번호 재설정 안내를 발송했습니다.") }
        }
    }

    @Test
    fun `회원 탈퇴는 사용자를 익명화하고 토큰을 무효화하며 재가입을 허용한다`() {
        val email = "withdraw@example.com"
        val password = "password123!"
        val token = signupAndLogin(email, password)
        val deletedUserId = jdbcTemplate.queryForObject("select id from users where email = ?", UUID::class.java, email)!!
        jdbcTemplate.update(
            "insert into user_visits(id, user_id, visited_on, created_at) values (?, ?, current_date, current_timestamp)",
            UUID.randomUUID(), deletedUserId,
        )
        jdbcTemplate.update(
            "insert into prayer_profiles(user_id, sunday_reminder_enabled, updated_at) values (?, true, current_timestamp)",
            deletedUserId,
        )
        jdbcTemplate.update(
            "insert into user_avatar_platforms(user_id, selected_code, updated_at) values (?, 'WOOD', current_timestamp)",
            deletedUserId,
        )
        jdbcTemplate.update(
            "insert into weekly_prayer_topics(id, user_id, week_start, content, visible_to_friends, created_at, updated_at) values (?, ?, current_date, ?, false, current_timestamp, current_timestamp)",
            UUID.randomUUID(), deletedUserId, "탈퇴 시 삭제할 기도제목",
        )
        jdbcTemplate.update(
            "insert into bible_notes(id, user_id, content, revision, created_at, updated_at) values (?, ?, ?, ?, current_timestamp, current_timestamp)",
            UUID.randomUUID(),
            deletedUserId,
            "회원탈퇴와 함께 지워져야 하는 개인 말씀 메모",
            0,
        )
        val testVersionId = UUID.randomUUID()
        jdbcTemplate.update(
            "insert into faith_test_versions(id, code, version, title, status, created_at) values (?, ?, ?, ?, ?, current_timestamp)",
            testVersionId,
            "WITHDRAWAL-${UUID.randomUUID()}",
            1,
            "회원탈퇴 삭제 검증",
            "PUBLISHED",
        )
        val assessmentId = UUID.randomUUID()
        jdbcTemplate.update(
            "insert into faith_assessments(id, user_id, test_version_id, status, started_at) values (?, ?, ?, ?, current_timestamp)",
            assessmentId,
            deletedUserId,
            testVersionId,
            "IN_PROGRESS",
        )

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
        assertEquals(0L, jdbcTemplate.queryForObject("select count(*) from bible_notes where user_id = ?", Long::class.java, deletedUserId) ?: -1L)
        assertEquals(0L, jdbcTemplate.queryForObject("select count(*) from user_visits where user_id = ?", Long::class.java, deletedUserId) ?: -1L)
        assertEquals(0L, jdbcTemplate.queryForObject("select count(*) from prayer_profiles where user_id = ?", Long::class.java, deletedUserId) ?: -1L)
        assertEquals(0L, jdbcTemplate.queryForObject("select count(*) from user_avatar_platforms where user_id = ?", Long::class.java, deletedUserId) ?: -1L)
        assertEquals(0L, jdbcTemplate.queryForObject("select count(*) from weekly_prayer_topics where user_id = ?", Long::class.java, deletedUserId) ?: -1L)
        assertEquals(0L, jdbcTemplate.queryForObject("select count(*) from faith_assessments where id = ?", Long::class.java, assessmentId) ?: -1L)

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
                  "displayName": "Rejoined"
                }
                """.trimIndent()
        }.andExpect {
            status { isCreated() }
            jsonPath("$.user.displayName") { value("Rejoined") }
        }
    }

    @Test
    fun `회원 탈퇴는 개인 데이터를 삭제하고 공유 기록을 익명화하며 캐시를 비운다`() {
        val email = "withdraw-data@example.com"
        val password = "password123!"
        val token = signupAndLogin(email, password)
        val userId = jdbcTemplate.queryForObject("select id from users where email = ?", UUID::class.java, email)!!
        val now = Instant.now()
        val conversationId = UUID.randomUUID()
        val answerTurnId = UUID.randomUUID()
        val readingStateId = UUID.randomUUID()
        val deviceId = UUID.randomUUID()
        val pastureId = UUID.randomUUID()

        jdbcTemplate.update(
            """
            insert into ai_conversations(
              id, user_id, plan_snapshot, status, encrypted_context, turn_count,
              expires_at, created_at, updated_at
            ) values (?, ?, 'FREE', 'ACTIVE', ?, 1, ?, ?, ?)
            """.trimIndent(),
            conversationId,
            userId,
            "encrypted-private-context",
            now.plusSeconds(3600),
            now,
            now,
        )
        jdbcTemplate.update(
            """
            insert into ai_answer_history(
              turn_id, user_id, question_ciphertext, response_ciphertext, assistant_version, response_schema_version, created_at
            ) values (?, ?, ?, ?, 'ai-pastor-v1', 'assistant-response-v2', ?)
            """.trimIndent(),
            answerTurnId,
            userId,
            "encrypted-question",
            "encrypted-answer",
            now,
        )
        jdbcTemplate.update(
            """
            insert into bible_reading_states(
              id, user_id, bible_version, book_code, chapter, last_verse, completed, last_read_at
            ) values (?, ?, 'KOR1910', 'JHN', 3, 16, false, ?)
            """.trimIndent(),
            readingStateId,
            userId,
            now,
        )
        jdbcTemplate.update(
            """
            insert into notification_devices(id, user_id, device_id, fcm_token, platform, active, updated_at)
            values (?, ?, 'withdraw-device', 'withdraw-fcm-token', 'ANDROID', true, ?)
            """.trimIndent(),
            deviceId,
            userId,
            now,
        )
        jdbcTemplate.update(
            """
            insert into quiet_time_pastures(id, name, leader_name, leader_user_id, invite_code, created_at)
            values (?, '삭제 정책 테스트 목장', '탈퇴 전 이름', ?, 'ERASE-TEST', ?)
            """.trimIndent(),
            pastureId,
            userId,
            now,
        )

        mockMvc.delete("/api/v1/auth/me") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"confirmation":"DELETE","currentPassword":"$password"}"""
        }.andExpect {
            status { isNoContent() }
        }

        assertEquals(0L, jdbcTemplate.queryForObject("select count(*) from ai_conversations where user_id = ?", Long::class.java, userId))
        assertEquals(0L, jdbcTemplate.queryForObject("select count(*) from ai_answer_history where user_id = ?", Long::class.java, userId))
        assertEquals(0L, jdbcTemplate.queryForObject("select count(*) from bible_reading_states where user_id = ?", Long::class.java, userId))
        assertEquals(0L, jdbcTemplate.queryForObject("select count(*) from notification_devices where user_id = ?", Long::class.java, userId))
        assertNull(jdbcTemplate.queryForObject("select leader_user_id from quiet_time_pastures where id = ?", UUID::class.java, pastureId))
        assertEquals("탈퇴한 사용자", jdbcTemplate.queryForObject("select leader_name from quiet_time_pastures where id = ?", String::class.java, pastureId))
        assertTrue(jdbcTemplate.queryForObject("select count(*) from account_erasure_audits", Long::class.java)!! > 0L)
        verify(aiCachePort).evictUser(userId)
    }

    @Test
    fun `이메일 계정 탈퇴에는 현재 비밀번호가 필요하다`() {
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
                  "displayName": "${uniqueTestNickname()}"
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
