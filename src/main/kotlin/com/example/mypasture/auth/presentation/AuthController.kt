package com.example.mypasture.auth.presentation

import com.example.mypasture.auth.application.AuthService
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
import com.example.mypasture.auth.presentation.dto.UpdateMyProfileRequest
import com.example.mypasture.config.OAuthProperties
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 인증 도메인의 HTTP endpoint를 제공하는 controller입니다. */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val authService: AuthService,
    private val emailVerificationService: EmailVerificationService,
    private val oauthProperties: OAuthProperties,
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
