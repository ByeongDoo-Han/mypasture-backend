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
import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
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
@Tag(name = "인증 · 계정", description = "회원가입, 로그인, 세션과 내 계정을 관리합니다.")
interface AuthApi {
    @Operation(summary = "닉네임 사용 가능 여부", description = "회원가입 전에 닉네임 형식과 중복 여부를 확인합니다.")
    @GetMapping("/nicknames/availability")
    fun nicknameAvailability(@Parameter(description = "형식과 중복 여부를 확인할 닉네임.") @RequestParam nickname: String): NicknameAvailabilityResponse

    @Operation(summary = "내 닉네임 변경 가능 여부", description = "로그인한 사용자가 닉네임 변경 전에 사용 가능 여부를 확인합니다.")
    @GetMapping("/me/nickname-availability")
    fun myNicknameAvailability(@AuthenticationPrincipal principal: JwtPrincipal, @Parameter(description = "형식과 중복 여부를 확인할 닉네임.") @RequestParam nickname: String): NicknameAvailabilityResponse

    @Operation(summary = "인증 서비스 상태 확인", description = "인증 API가 요청을 처리할 수 있는지 간단한 상태를 반환합니다.")
    @GetMapping("/health")
    fun health(): Map<String, String>

    @Operation(summary = "소셜 로그인 제공자 상태", description = "클라이언트에 사용 가능한 소셜 로그인 제공자를 알립니다. 서버 비밀값은 반환하지 않습니다.")
    @GetMapping("/social-login/providers")
    fun socialProviderReadiness(): SocialProviderReadinessResponse

    @Operation(summary = "회원가입 이메일 인증 요청", description = "회원가입용 이메일 인증 코드를 발송하도록 요청합니다. 처리 요청을 받으면 202를 반환합니다.")
    @PostMapping("/email-verifications")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun requestEmailVerification(
        @Valid @RequestBody request: EmailVerificationRequest,
    ): EmailVerificationRequestedResponse

    @Operation(summary = "회원가입 이메일 인증 확인", description = "이메일로 받은 인증 코드를 검증해 가입에 사용할 인증 결과를 반환합니다.")
    @PostMapping("/email-verifications/confirm")
    fun confirmEmailVerification(
        @Valid @RequestBody request: EmailVerificationConfirmRequest,
    ): EmailVerificationConfirmedResponse

    @Operation(summary = "이메일 회원가입", description = "검증된 이메일과 닉네임 등 가입 정보를 사용해 계정을 생성합니다.")
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    fun signup(@Valid @RequestBody request: UserSignupRequest): UserSignupResponse

    @Operation(summary = "이메일 로그인", description = "이메일과 비밀번호를 확인하고 로그인 결과를 반환합니다.")
    @PostMapping("/login")
    fun login(@Valid @RequestBody request: UserLoginRequest): UserLoginResponse

    @Operation(summary = "소셜 로그인", description = "소셜 제공자의 인증 결과를 서버에서 검증한 뒤 기존 계정에 로그인하거나 새 계정을 생성합니다.")
    @PostMapping("/social-login")
    fun socialLogin(@Valid @RequestBody request: SocialLoginRequest): UserLoginResponse

    @Operation(summary = "웹 카카오 로그인", description = "웹 카카오 인가 코드를 서버에서 교환·검증해 로그인 결과를 반환합니다.")
    @PostMapping("/social-login/kakao/web")
    fun webKakaoLogin(@Valid @RequestBody request: KakaoWebLoginRequest): UserLoginResponse

    @Operation(summary = "소셜 로그인 챌린지 발급", description = "소셜 로그인 검증에 사용할 일회성 챌린지를 생성합니다.")
    @PostMapping("/social-login/challenges")
    fun createSocialLoginChallenge(): SocialLoginChallengeResponse

    @Operation(summary = "액세스 토큰 갱신", description = "유효한 리프레시 토큰으로 새 로그인 토큰을 발급합니다.")
    @PostMapping("/refresh")
    fun refresh(@Valid @RequestBody request: RefreshAccessTokenRequest): UserLoginResponse

    @Operation(summary = "웹 세션 쿠키 생성", description = "리프레시 토큰을 검증해 브라우저용 보안 쿠키 세션을 설정합니다.")
    @PostMapping("/web/session")
    fun createWebSession(
        @Parameter(description = "웹 요청의 Origin 헤더. 서버가 허용된 출처인지 검사합니다.")
        @RequestHeader(value = HttpHeaders.ORIGIN, required = false) origin: String?,
        @Valid @RequestBody request: RefreshAccessTokenRequest,
    ): ResponseEntity<WebSessionResponse>

    @Operation(summary = "웹 세션 갱신", description = "유효한 브라우저 세션 쿠키를 확인해 웹 로그인 상태를 갱신합니다.")
    @PostMapping("/web/refresh")
    fun refreshWebSession(
        @Parameter(description = "웹 요청의 Origin 헤더. 서버가 허용된 출처인지 검사합니다.")
        @RequestHeader(value = HttpHeaders.ORIGIN, required = false) origin: String?,
        @Parameter(description = "브라우저의 HttpOnly 리프레시 세션 쿠키.")
        @CookieValue(value = WebSessionCookie.NAME, required = false) refreshToken: String?,
    ): ResponseEntity<WebSessionResponse>

    @Operation(summary = "웹 세션 로그아웃", description = "브라우저의 세션 쿠키를 폐기하고 웹 세션을 종료합니다.")
    @PostMapping("/web/logout")
    fun logoutWebSession(
        @Parameter(description = "웹 요청의 Origin 헤더. 서버가 허용된 출처인지 검사합니다.")
        @RequestHeader(value = HttpHeaders.ORIGIN, required = false) origin: String?,
        @Parameter(description = "폐기할 브라우저의 HttpOnly 리프레시 세션 쿠키.")
        @CookieValue(value = WebSessionCookie.NAME, required = false) refreshToken: String?,
    ): ResponseEntity<Void>

    @Operation(summary = "로그아웃", description = "로그인한 사용자의 현재 인증 세션을 종료합니다.")
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout(@AuthenticationPrincipal principal: JwtPrincipal)

    @Operation(summary = "비밀번호 재설정 요청", description = "이메일로 비밀번호 재설정 코드를 발송하도록 요청합니다. 처리 요청을 받으면 202를 반환합니다.")
    @PostMapping("/password-reset/requests")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun requestPasswordReset(@Valid @RequestBody request: PasswordResetRequest): PasswordResetRequestedResponse

    @Operation(summary = "비밀번호 재설정 확인", description = "재설정 코드와 새 비밀번호를 검증해 비밀번호를 변경합니다.")
    @PostMapping("/password-reset/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun confirmPasswordReset(@Valid @RequestBody request: PasswordResetConfirmRequest)

    @Operation(summary = "내 계정 조회", description = "현재 로그인한 사용자의 계정과 프로필 정보를 반환합니다.")
    @GetMapping("/me")
    fun me(@AuthenticationPrincipal principal: JwtPrincipal): UserDto

    @Operation(summary = "내 프로필 수정", description = "로그인한 사용자의 프로필과 닉네임을 변경합니다.")
    @PatchMapping("/me")
    fun updateMe(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Valid @RequestBody request: UpdateMyProfileRequest,
    ): UserDto

    @Operation(summary = "내 계정 삭제", description = "본인 확인 후 계정을 탈퇴 처리합니다. 관련 데이터는 서버의 탈퇴 정책에 따라 정리됩니다.")
    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteMe(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Valid @RequestBody request: DeleteAccountRequest,
    )
}
