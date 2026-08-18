package com.example.mypasture.config

import kotlin.test.Test
import kotlin.test.assertEquals

/** 소셜 로그인 Client ID 환경변수 조합 규칙을 검증합니다. */
class OAuthPropertiesTests {
    @Test
    fun `플랫폼별 client id를 중복 없이 합친다`() {
        val properties = OidcProviderProperties(
            clientIds = listOf("common-client"),
            webClientIds = listOf(" web-client "),
            iosClientIds = listOf("ios-client"),
            androidClientIds = listOf("android-client", "common-client", ""),
        )

        assertEquals(
            listOf("common-client", "web-client", "ios-client", "android-client"),
            properties.acceptedClientIds(),
        )
    }
}
