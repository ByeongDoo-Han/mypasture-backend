package com.example.mypasture.prayer.application

import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** 광고를 켜기 전에도 서명된 AdMob URL 확인 요청만 보상 없이 수락합니다. */
@Component
class PrayerAdProbeService(private val ads: PrayerAdsConfig, private val clock: Clock = Clock.systemUTC()) {
    fun verify(signedAt: Instant) {
        val age = Duration.between(signedAt, Instant.now(clock))
        if (!ads.hasConfiguredUnit() || age < Duration.ofMinutes(-1) || age > Duration.ofMinutes(5))
            throw PrayerConflictException("광고 URL 확인 요청이 유효하지 않습니다.")
    }
}
