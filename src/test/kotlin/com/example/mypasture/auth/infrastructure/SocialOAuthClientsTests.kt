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
    fun `google login requires id token`() {
        assertFailsWith<MissingSocialTokenException> {
            GoogleOidcOAuthClient(properties).fetchProfile(SocialLoginCommand(AuthProvider.GOOGLE, null, null, null))
        }
    }

    @Test
    fun `apple login requires id token`() {
        assertFailsWith<MissingSocialTokenException> {
            AppleOidcOAuthClient(properties).fetchProfile(SocialLoginCommand(AuthProvider.APPLE, null, null, null))
        }
    }

    @Test
    fun `kakao login requires access token`() {
        assertFailsWith<MissingSocialTokenException> {
            KakaoOAuthClient(properties).fetchProfile(SocialLoginCommand(AuthProvider.KAKAO, null, null, null))
        }
    }
}
