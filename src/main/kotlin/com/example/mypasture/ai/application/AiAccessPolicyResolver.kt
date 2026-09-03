package com.example.mypasture.ai.application

import com.example.mypasture.config.AiProperties
import com.example.mypasture.user.domain.SubscriptionTier
import org.springframework.stereotype.Component
import java.time.Instant

/** 서버가 관리하는 실제 구독 등급만으로 AI 대화 방식과 일일 한도를 결정합니다. */
@Component
class AiAccessPolicyResolver(
    private val properties: AiProperties,
) {
    fun resolve(subscriptionTier: SubscriptionTier): AiAccessPolicy {
        if (subscriptionTier == SubscriptionTier.PREMIUM) {
            return AiAccessPolicy(SubscriptionTier.PREMIUM, properties.premiumDailySafetyLimit.toLong(), true, null)
        }
        return AiAccessPolicy(SubscriptionTier.FREE, properties.freeDailyLimit.toLong(), false, null)
    }
}

data class AiAccessPolicy(
    val effectiveTier: SubscriptionTier,
    val reservationDailyLimit: Long,
    val unlimited: Boolean,
    val entitlementExpiresAt: Instant?,
)
