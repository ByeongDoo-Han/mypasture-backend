package com.example.mypasture.auth.infrastructure

import com.example.mypasture.auth.application.SocialLoginCommand
import com.example.mypasture.auth.domain.AuthProvider
import com.example.mypasture.config.OAuthProperties
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** 외부 OAuth 호출 전에 제공자별 필수 토큰을 검사하는 adapter 단위 테스트입니다. */
class SocialOAuthClientsTests {
    private val properties = OAuthProperties()

    @Test
    fun `구글 로그인에는 ID 토큰이 필요하다`() {
        assertFailsWith<MissingSocialTokenException> {
            GoogleOidcOAuthClient(properties).fetchProfile(SocialLoginCommand(AuthProvider.GOOGLE, null, null, null))
        }
    }

    @Test
    fun `애플 로그인에는 ID 토큰이 필요하다`() {
        assertFailsWith<MissingSocialTokenException> {
            AppleOidcOAuthClient(properties).fetchProfile(SocialLoginCommand(AuthProvider.APPLE, null, null, null))
        }
    }

    @Test
    fun `카카오 로그인에는 액세스 토큰이 필요하다`() {
        assertFailsWith<MissingSocialTokenException> {
            KakaoOAuthClient(properties).fetchProfile(SocialLoginCommand(AuthProvider.KAKAO, null, null, null))
        }
    }
}
