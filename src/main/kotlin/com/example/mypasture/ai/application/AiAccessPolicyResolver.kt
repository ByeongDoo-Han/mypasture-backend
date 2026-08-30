package com.example.mypasture.ai.application

import com.example.mypasture.config.AiProperties
import com.example.mypasture.coupon.application.UserEntitlementService
import com.example.mypasture.user.domain.SubscriptionTier
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/** 유료 구독과 기간제 쿠폰 권한을 합쳐 AI 대화 방식과 실제 일일 한도를 결정합니다. */
@Component
class AiAccessPolicyResolver(
    private val entitlements: UserEntitlementService,
    private val properties: AiProperties,
) {
    fun resolve(userId: UUID, subscriptionTier: SubscriptionTier, now: Instant = Instant.now()): AiAccessPolicy {
        if (subscriptionTier == SubscriptionTier.PREMIUM) {
            return AiAccessPolicy(SubscriptionTier.PREMIUM, properties.premiumDailySafetyLimit.toLong(), true, null)
        }
        val trial = entitlements.activePremiumAiTrial(userId, now)
        if (trial != null) {
            return AiAccessPolicy(
                effectiveTier = SubscriptionTier.PREMIUM,
                reservationDailyLimit = (trial.aiDailyLimit ?: 30).toLong(),
                unlimited = false,
                entitlementExpiresAt = trial.expiresAt,
            )
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
