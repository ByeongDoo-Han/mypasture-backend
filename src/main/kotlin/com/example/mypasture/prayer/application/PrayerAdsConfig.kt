package com.example.mypasture.prayer.application

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/** 운영 광고 단위가 설정된 경우에만 추가 편지 발송권을 활성화합니다. */
@Component
class PrayerAdsConfig(
    @Value("\${app.prayer.ads.enabled:false}") val enabled: Boolean,
    @Value("\${app.prayer.ads.android-unit-id:}") private val androidUnitId: String,
    @Value("\${app.prayer.ads.ios-unit-id:}") private val iosUnitId: String,
) {
    init {
        if (enabled) require(androidUnitId.isNotBlank() && iosUnitId.isNotBlank()) {
            "Both AdMob prayer reward ad unit IDs are required when ads are enabled."
        }
    }

    fun unitFor(platform: String): String = when (platform.uppercase()) {
        "ANDROID" -> androidUnitId
        "IOS" -> iosUnitId
        else -> throw IllegalArgumentException("지원하지 않는 광고 플랫폼입니다.")
    }.also { if (it.isBlank()) throw PrayerConflictException("광고가 설정되지 않았습니다.") }
}
