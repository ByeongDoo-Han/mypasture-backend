package com.example.mypasture.auth.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.auth.presentation.dto.DeleteAccountRequest
import com.example.mypasture.auth.presentation.dto.EmailVerificationConfirmRequest
import com.example.mypasture.auth.presentation.dto.EmailVerificationConfirmedResponse
import com.example.mypasture.auth.presentation.dto.EmailVerificationRequest
import com.example.mypasture.auth.presentation.dto.EmailVerificationRequestedResponse
import com.example.mypasture.auth.presentation.dto.KakaoWebLoginRequest
import com.example.mypasture.auth.presentation.dto.PasswordResetConfirmRequest
import com.example.mypasture.auth.presentation.dto.PasswordResetRequest
import com.example.mypasture.auth.presentation.dto.PasswordResetRequestedResponse
import com.example.mypasture.auth.presentation.dto.RefreshAccessTokenRequest
import com.example.mypasture.auth.presentation.dto.SocialLoginRequest
import com.example.mypasture.auth.presentation.dto.SocialLoginChallengeResponse
import com.example.mypasture.auth.presentation.dto.UserDto
import com.example.mypasture.auth.presentation.dto.UserLoginRequest
import com.example.mypasture.auth.presentation.dto.UserLoginResponse
import com.example.mypasture.auth.presentation.dto.WebSessionResponse
import com.example.mypasture.auth.presentation.dto.UserSignupRequest
import com.example.mypasture.auth.presentation.dto.UserSignupResponse
import com.example.mypasture.auth.presentation.dto.UpdateMyProfileRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.RequestHeader

/** 비밀값을 노출하지 않고 서버가 검증 가능한 소셜 제공자를 앱에 알려주는 응답입니다. */
data class SocialProviderReadinessResponse(
    val providers: Map<String, Boolean>,
)

/** 닉네임 중복 확인 결과입니다. 최종 저장 시 서버에서 다시 검사합니다. */
data class NicknameAvailabilityResponse(val available: Boolean)

/** Swagger/OpenAPI 문서화를 controller 구현과 분리하기 위한 인증 API contract입니다. */
interface AuthApi {
    @GetMapping("/nicknames/availability")
    fun nicknameAvailability(@RequestParam nickname: String): NicknameAvailabilityResponse

    @GetMapping("/me/nickname-availability")
    fun myNicknameAvailability(@AuthenticationPrincipal principal: JwtPrincipal, @RequestParam nickname: String): NicknameAvailabilityResponse

    @GetMapping("/health")
    fun health(): Map<String, String>

    @GetMapping("/social-login/providers")
    fun socialProviderReadiness(): SocialProviderReadinessResponse

    @PostMapping("/email-verifications")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun requestEmailVerification(
        @Valid @RequestBody request: EmailVerificationRequest,
    ): EmailVerificationRequestedResponse

    @PostMapping("/email-verifications/confirm")
    fun confirmEmailVerification(
        @Valid @RequestBody request: EmailVerificationConfirmRequest,
    ): EmailVerificationConfirmedResponse

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    fun signup(@Valid @RequestBody request: UserSignupRequest): UserSignupResponse

    @PostMapping("/login")
    fun login(@Valid @RequestBody request: UserLoginRequest): UserLoginResponse

    @PostMapping("/social-login")
    fun socialLogin(@Valid @RequestBody request: SocialLoginRequest): UserLoginResponse

    @PostMapping("/social-login/kakao/web")
    fun webKakaoLogin(@Valid @RequestBody request: KakaoWebLoginRequest): UserLoginResponse

    @PostMapping("/social-login/challenges")
    fun createSocialLoginChallenge(): SocialLoginChallengeResponse

    @PostMapping("/refresh")
    fun refresh(@Valid @RequestBody request: RefreshAccessTokenRequest): UserLoginResponse

    @PostMapping("/web/session")
    fun createWebSession(
        @RequestHeader(value = HttpHeaders.ORIGIN, required = false) origin: String?,
        @Valid @RequestBody request: RefreshAccessTokenRequest,
    ): ResponseEntity<WebSessionResponse>

    @PostMapping("/web/refresh")
    fun refreshWebSession(
        @RequestHeader(value = HttpHeaders.ORIGIN, required = false) origin: String?,
        @CookieValue(value = WebSessionCookie.NAME, required = false) refreshToken: String?,
    ): ResponseEntity<WebSessionResponse>

    @PostMapping("/web/logout")
    fun logoutWebSession(
        @RequestHeader(value = HttpHeaders.ORIGIN, required = false) origin: String?,
        @CookieValue(value = WebSessionCookie.NAME, required = false) refreshToken: String?,
    ): ResponseEntity<Void>

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout(@AuthenticationPrincipal principal: JwtPrincipal)

    @PostMapping("/password-reset/requests")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun requestPasswordReset(@Valid @RequestBody request: PasswordResetRequest): PasswordResetRequestedResponse

    @PostMapping("/password-reset/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun confirmPasswordReset(@Valid @RequestBody request: PasswordResetConfirmRequest)

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal principal: JwtPrincipal): UserDto

    @PatchMapping("/me")
    fun updateMe(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Valid @RequestBody request: UpdateMyProfileRequest,
    ): UserDto

    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteMe(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Valid @RequestBody request: DeleteAccountRequest,
    )
}
