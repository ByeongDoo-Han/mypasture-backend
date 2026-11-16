package com.example.mypasture.auth.presentation

import com.example.mypasture.auth.application.AuthService
import com.example.mypasture.auth.application.InvalidRefreshTokenException
import com.example.mypasture.auth.application.EmailVerificationService
import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.auth.presentation.dto.DeleteAccountRequest
import com.example.mypasture.auth.presentation.dto.EmailVerificationConfirmRequest
import com.example.mypasture.auth.presentation.dto.EmailVerificationRequest
import com.example.mypasture.auth.presentation.dto.KakaoWebLoginRequest
import com.example.mypasture.auth.presentation.dto.PasswordResetConfirmRequest
import com.example.mypasture.auth.presentation.dto.PasswordResetRequest
import com.example.mypasture.auth.presentation.dto.RefreshAccessTokenRequest
import com.example.mypasture.auth.presentation.dto.SocialLoginRequest
import com.example.mypasture.auth.presentation.dto.UserLoginRequest
import com.example.mypasture.auth.presentation.dto.UserSignupRequest
import com.example.mypasture.auth.presentation.dto.WebSessionResponse
import com.example.mypasture.auth.presentation.dto.UpdateMyProfileRequest
import com.example.mypasture.config.CorsProperties
import com.example.mypasture.config.JwtProperties
import com.example.mypasture.config.OAuthProperties
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 인증 도메인의 HTTP endpoint를 제공하는 controller입니다. */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val authService: AuthService,
    private val emailVerificationService: EmailVerificationService,
    private val oauthProperties: OAuthProperties,
    private val corsProperties: CorsProperties,
    private val jwtProperties: JwtProperties,
) : AuthApi {
    override fun nicknameAvailability(nickname: String) = NicknameAvailabilityResponse(authService.nicknameAvailable(nickname))

    override fun myNicknameAvailability(principal: JwtPrincipal, nickname: String) =
        NicknameAvailabilityResponse(authService.nicknameAvailable(nickname, principal.userId))

    override fun health(): Map<String, String> = mapOf("status" to "auth-ready")

    override fun socialProviderReadiness() = SocialProviderReadinessResponse(
        providers = mapOf(
            "GOOGLE" to oauthProperties.google.acceptedClientIds().isNotEmpty(),
            "APPLE" to oauthProperties.apple.acceptedClientIds().isNotEmpty(),
            "KAKAO" to oauthProperties.kakao.appIds.isNotEmpty(),
            "KAKAO_WEB" to (oauthProperties.kakao.appIds.isNotEmpty() && oauthProperties.kakao.web.isConfigured()),
        ),
    )

    override fun requestEmailVerification(request: EmailVerificationRequest) =
        emailVerificationService.request(request)

    override fun confirmEmailVerification(request: EmailVerificationConfirmRequest) =
        emailVerificationService.confirm(request)

    override fun signup(request: UserSignupRequest) = authService.signup(request)

    override fun login(request: UserLoginRequest) = authService.login(request)

    override fun socialLogin(request: SocialLoginRequest) = authService.socialLogin(request)

    override fun webKakaoLogin(request: KakaoWebLoginRequest) = authService.webKakaoLogin(request)

    override fun createSocialLoginChallenge() = authService.createSocialLoginChallenge()

    override fun refresh(request: RefreshAccessTokenRequest) = authService.refresh(request)

    override fun createWebSession(origin: String?, request: RefreshAccessTokenRequest): ResponseEntity<WebSessionResponse> {
        requireWebOrigin(origin)
        return webSessionResponse(authService.refresh(request))
    }

    override fun refreshWebSession(origin: String?, refreshToken: String?): ResponseEntity<WebSessionResponse> {
        requireWebOrigin(origin)
        val token = requireCookieToken(refreshToken)
        return webSessionResponse(authService.refresh(RefreshAccessTokenRequest(token)))
    }

    override fun logoutWebSession(origin: String?, refreshToken: String?): ResponseEntity<Void> {
        requireWebOrigin(origin)
        try {
            if (refreshToken != null) {
                val session = authService.refresh(RefreshAccessTokenRequest(requireCookieToken(refreshToken)))
                authService.logout(session.user.id)
            }
        } catch (_: InvalidRefreshTokenException) {
            // 만료·재사용된 쿠키도 브라우저에서는 반드시 제거합니다.
        }
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, WebSessionCookie.cleared()).header(HttpHeaders.CACHE_CONTROL, "no-store").build()
    }

    private fun requireWebOrigin(origin: String?) {
        if (origin == null || origin !in corsProperties.webSessionAllowedOrigins || origin == "*") {
            throw InvalidWebSessionOriginException()
        }
    }

    private fun requireCookieToken(token: String?): String =
        token?.takeIf { it.isNotBlank() && it.length <= 1000 } ?: throw InvalidRefreshTokenException()

    private fun webSessionResponse(session: com.example.mypasture.auth.presentation.dto.UserLoginResponse): ResponseEntity<WebSessionResponse> =
        ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, WebSessionCookie.issued(session.refreshToken, jwtProperties.refreshTokenTtlSeconds))
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(WebSessionResponse.from(session))


    override fun logout(principal: JwtPrincipal) = authService.logout(principal.userId)

    override fun requestPasswordReset(request: PasswordResetRequest) =
        authService.requestPasswordReset(request)

    override fun confirmPasswordReset(request: PasswordResetConfirmRequest) =
        authService.confirmPasswordReset(request)

    override fun me(principal: JwtPrincipal) = authService.me(principal.userId)

    override fun updateMe(principal: JwtPrincipal, request: UpdateMyProfileRequest) =
        authService.updateProfile(principal.userId, request)

    override fun deleteMe(
        principal: JwtPrincipal,
        request: DeleteAccountRequest,
    ) = authService.deleteAccount(principal.userId, principal.issuedAt, request)
}

/** 허용된 웹 출처가 아닌 세션 쿠키 요청을 거부합니다. */
class InvalidWebSessionOriginException : RuntimeException()
