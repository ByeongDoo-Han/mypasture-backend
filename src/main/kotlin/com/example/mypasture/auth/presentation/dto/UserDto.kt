package com.example.mypasture.auth.presentation.dto

import com.example.mypasture.auth.domain.AuthProvider
import com.example.mypasture.user.domain.SubscriptionTier
import com.example.mypasture.user.domain.UserEntity
import com.example.mypasture.user.domain.UserRole
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

/** 인증 응답에서 내려주는 사용자 공개 프로필 DTO입니다. */
data class UserDto(
    val id: UUID,
    val email: String?,
    val displayName: String,
    val role: UserRole,
    val subscriptionTier: SubscriptionTier,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(user: UserEntity): UserDto =
            UserDto(
                id = user.id,
                email = user.email,
                displayName = user.displayName,
                role = user.role,
                subscriptionTier = user.subscriptionTier,
                createdAt = user.createdAt,
                updatedAt = user.updatedAt,
            )
    }
}

/** 이메일 회원가입 요청 DTO입니다. */
data class UserSignupRequest(
    @field:NotBlank
    @field:Email
    @field:Size(max = 320)
    val email: String,
    @field:NotBlank
    @field:Size(min = 8, max = 72)
    val password: String,
    @field:NotBlank
    @field:Size(min = 1, max = 80)
    val displayName: String,
    @field:Size(max = 1000)
    val verificationToken: String? = null,
)

/** 회원가입 전에 이메일로 인증번호 발송을 요청하는 DTO입니다. */
data class EmailVerificationRequest(
    @field:NotBlank
    @field:Email
    @field:Size(max = 320)
    val email: String,
)

/** 계정 존재 여부와 무관하게 동일하게 반환하는 이메일 인증번호 요청 응답입니다. */
data class EmailVerificationRequestedResponse(
    val message: String = "인증 가능한 이메일이라면 인증번호를 발송했습니다.",
    val expiresInSeconds: Long,
    val resendAfterSeconds: Long,
)

/** 이메일과 6자리 인증번호를 확인하는 요청 DTO입니다. */
data class EmailVerificationConfirmRequest(
    @field:NotBlank
    @field:Email
    @field:Size(max = 320)
    val email: String,
    @field:Pattern(regexp = "^[0-9]{6}$")
    val code: String,
)

/** 확인된 이메일로 회원가입할 때 한 번만 사용할 수 있는 증명을 반환합니다. */
data class EmailVerificationConfirmedResponse(
    val verificationToken: String,
    val expiresAt: Instant,
)

/** 이메일 로그인 요청 DTO입니다. */
data class UserLoginRequest(
    @field:NotBlank
    @field:Email
    @field:Size(max = 320)
    val email: String,
    @field:NotBlank
    @field:Size(min = 8, max = 72)
    val password: String,
)

/** Google/Apple/Kakao 로그인 요청 DTO입니다. 제공자별로 idToken 또는 accessToken 중 필요한 값을 전달합니다. */
data class SocialLoginRequest(
    val provider: AuthProvider,
    val challengeId: UUID? = null,
    @field:Size(max = 5000)
    val idToken: String? = null,
    @field:Size(max = 5000)
    val accessToken: String? = null,
    @field:Size(max = 80)
    val displayName: String? = null,
)

/** 클라이언트가 OIDC 요청에 넣고 서버가 일회성으로 검증하는 nonce challenge입니다. */
data class SocialLoginChallengeResponse(
    val challengeId: UUID,
    val nonce: String,
    val expiresAt: Instant,
)

/** 회원가입 완료 응답 DTO입니다. */
data class UserSignupResponse(
    val user: UserDto,
)

/** 로그인 완료 응답 DTO입니다. */
data class UserLoginResponse(
    val token: String,
    val refreshToken: String,
    val tokenType: String,
    val expiresIn: Long,
    val expiresAt: Instant,
    val user: UserDto,
)

/** 만료된 access token을 회전형 refresh token으로 갱신하는 요청입니다. */
data class RefreshAccessTokenRequest(
    @field:NotBlank
    @field:Size(max = 1000)
    val refreshToken: String,
)

/** 계정 존재 여부를 노출하지 않고 비밀번호 재설정을 요청하는 DTO입니다. */
data class PasswordResetRequest(
    @field:NotBlank
    @field:Email
    @field:Size(max = 320)
    val email: String,
)

/** 비밀번호 재설정 요청의 이메일 열거 공격 방지용 공통 응답입니다. */
data class PasswordResetRequestedResponse(
    val message: String = "가입된 이메일이라면 비밀번호 재설정 안내를 발송했습니다.",
)

/** 일회성 토큰으로 새 비밀번호를 확정하는 요청 DTO입니다. */
data class PasswordResetConfirmRequest(
    @field:NotBlank
    @field:Size(max = 1000)
    val token: String,
    @field:NotBlank
    @field:Size(min = 8, max = 72)
    val newPassword: String,
)

/** 현재 사용자가 회원탈퇴 의사를 명시하고 필요하면 비밀번호로 재인증하는 요청 DTO입니다. */
data class DeleteAccountRequest(
    @field:NotBlank
    @field:Pattern(regexp = "DELETE")
    val confirmation: String,
    @field:Size(min = 8, max = 72)
    val currentPassword: String? = null,
)
