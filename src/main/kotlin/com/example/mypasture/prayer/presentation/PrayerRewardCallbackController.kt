package com.example.mypasture.prayer.presentation

import com.example.mypasture.prayer.application.PrayerService
import com.example.mypasture.prayer.infrastructure.AdmobPrayerRewardVerifier
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 서명된 Google 콜백만 주간 편지 발송권 원장에 반영합니다. */
@RestController
@RequestMapping("/api/v1/prayer/admob")
class PrayerRewardCallbackController(
    private val verifier: AdmobPrayerRewardVerifier,
    private val service: PrayerService,
) : PrayerRewardCallbackApi {
    override fun callback(request: HttpServletRequest) {
        val reward = verifier.verify(request.queryString ?: "")
        service.claimAdReward(reward.intentId, reward.transactionId, reward.adUnitId, reward.signedAt)
    }
}
