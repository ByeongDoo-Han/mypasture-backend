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
    @field:Size(max = 5000)
    val idToken: String? = null,
    @field:Size(max = 5000)
    val accessToken: String? = null,
    @field:Size(max = 80)
    val displayName: String? = null,
)

/** 회원가입 완료 응답 DTO입니다. */
data class UserSignupResponse(
    val user: UserDto,
)

/** 로그인 완료 응답 DTO입니다. */
data class UserLoginResponse(
    val token: String,
    val tokenType: String,
    val expiresIn: Long,
    val expiresAt: Instant,
    val user: UserDto,
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
