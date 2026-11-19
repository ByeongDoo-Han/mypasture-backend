package com.example.mypasture.prayer.application

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** 광고 비활성 상태의 URL 확인과 실제 보상용 광고 단위 일치 범위를 검증합니다. */
class PrayerAdProbeServiceTests {
    private val android = "ca-app-pub-1234567890123456/1234567890"
    private val ios = "ca-app-pub-1234567890123456/1234567891"
    private val ads = PrayerAdsConfig(false, android, ios)
    private val now = Instant.parse("2026-11-19T00:00:00Z")
    private val probes = PrayerAdProbeService(ads, Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun `광고가 꺼져도 URL 확인은 서명 시각만 확인한다`() {
        assertDoesNotThrow { probes.verify(now) }
        assertDoesNotThrow { probes.verify(now.minusSeconds(300)) }
        assertDoesNotThrow { probes.verify(now.plusSeconds(60)) }
        assertTrue(ads.matchesUnit(android, android))
        assertTrue(ads.matchesUnit(android, "1234567890"))
        assertFalse(ads.matchesUnit(android, "1234567891"))
    }

    @Test
    fun `광고 단위 설정이 없거나 오래되거나 미래의 확인 요청은 거절한다`() {
        assertThrows(PrayerConflictException::class.java) { probes.verify(now.minusSeconds(301)) }
        assertThrows(PrayerConflictException::class.java) { probes.verify(now.plusSeconds(61)) }
        assertThrows(PrayerConflictException::class.java) {
            PrayerAdProbeService(PrayerAdsConfig(false, "", ""), Clock.fixed(now, ZoneOffset.UTC)).verify(now)
        }
    }
}
