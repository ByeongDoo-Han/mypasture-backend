package com.example.mypasture.prayer.presentation

import com.example.mypasture.prayer.infrastructure.AdmobPrayerRewardVerifier
import com.example.mypasture.prayer.infrastructure.PrayerAdIntentRepository
import com.example.mypasture.prayer.infrastructure.PrayerRewardKeyProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.Signature
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** 실제 HTTP 콜백에서 서명된 확인 요청만 보상 없이 수락하는지 검증합니다. */
@SpringBootTest(properties = ["app.bible.import-enabled=false", "app.firebase.enabled=false", "app.prayer.ads.enabled=false",
    "app.prayer.ads.android-unit-id=ca-app-pub-1234567890123456/4444444444",
    "app.prayer.ads.ios-unit-id=ca-app-pub-1234567890123456/5555555555"])
@AutoConfigureMockMvc
@Import(PrayerRewardCallbackTests.TestKeys::class)
class PrayerRewardCallbackTests(
    @Autowired private val mvc: MockMvc,
    @Autowired private val intents: PrayerAdIntentRepository,
) {
    @TestConfiguration
    class TestKeys {
        @Bean @Primary
        fun prayerRewardKeyProvider(): PrayerRewardKeyProvider = object : PrayerRewardKeyProvider {
            override fun keys(): Map<Long, PublicKey> = mapOf(7L to keys.public)
            override fun refresh(): Map<Long, PublicKey> = keys()
        }
    }

    @Test
    fun `콘솔의 시험 광고 단위로 서명된 URL 확인은 광고가 꺼져도 보상 없이 성공한다`() {
        val before = intents.count()
        val message = "ad_unit=1234567890&custom_data=${AdmobPrayerRewardVerifier.SSV_URL_VERIFICATION_MARKER}&timestamp=${Instant.now().toEpochMilli()}&transaction_id=probe-123"
        mvc.get("/api/v1/prayer/admob/ssv?${signed(message)}").andExpect { status { isOk() } }
        assertEquals(before, intents.count())
        mvc.get("/api/v1/prayer/admob/ssv?${signed(message.replace("ad_unit=1234567890&", ""))}")
            .andExpect { status { isConflict() } }
        mvc.get("/api/v1/prayer/admob/ssv?${signed(message).replace("probe-123", "probe-124")}")
            .andExpect { status { isConflict() } }
        assertEquals(before, intents.count())
    }

    @Test
    fun `일반 보상 요청은 광고가 꺼져 있으면 거절한다`() {
        val before = intents.count()
        val message = "ad_unit=1234567890&custom_data=${UUID.randomUUID()}&timestamp=${Instant.now().toEpochMilli()}&transaction_id=reward-123"
        mvc.get("/api/v1/prayer/admob/ssv?${signed(message)}").andExpect { status { isConflict() } }
        assertEquals(before, intents.count())
    }

    private fun signed(message: String): String {
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keys.private)
            update(message.toByteArray(StandardCharsets.UTF_8))
            Base64.getUrlEncoder().withoutPadding().encodeToString(sign())
        }
        return "$message&signature=$signature&key_id=7"
    }

    companion object {
        private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    }
}
