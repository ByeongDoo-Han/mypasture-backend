package com.example.mypasture.auth.application

import com.example.mypasture.auth.domain.AuthProvider

/** 외부 소셜 제공자의 토큰을 검증하고 My Pasture에서 사용할 표준 프로필로 변환하는 application port입니다. */
interface SocialOAuthClient {
    val provider: AuthProvider

    fun fetchProfile(command: SocialLoginCommand): SocialUserProfile
}

/** 소셜 로그인 use case 내부에서 사용하는 제공자 토큰 command입니다. */
data class SocialLoginCommand(
    val provider: AuthProvider,
    val idToken: String?,
    val accessToken: String?,
    val displayNameHint: String?,
)

/** 제공자별 응답 차이를 감춘 표준 소셜 사용자 프로필입니다. */
data class SocialUserProfile(
    val provider: AuthProvider,
    val providerUserId: String,
    val email: String?,
    val emailVerified: Boolean,
    val displayName: String?,
    val nonce: String? = null,
)
