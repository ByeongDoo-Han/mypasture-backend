package com.example.mypasture.ai.application

import com.example.mypasture.config.AiProperties
import com.example.mypasture.user.domain.SubscriptionTier
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID
import com.example.mypasture.billing.application.BillingLedgerService
import com.example.mypasture.billing.infrastructure.BillingProperties

/** 서버가 관리하는 실제 구독 등급만으로 AI 대화 방식과 일일 한도를 결정합니다. */
@Component
class AiAccessPolicyResolver(
    private val properties: AiProperties,
    private val billingProperties: BillingProperties,
    private val ledger: BillingLedgerService,
) {
    fun resolve(subscriptionTier: SubscriptionTier, userId: UUID? = null): AiAccessPolicy {
        val expiresAt = if (billingProperties.enabled && userId != null) ledger.premiumExpiresAt(userId, billingProperties.environment) else null
        val premium = if (billingProperties.enabled) expiresAt != null else subscriptionTier == SubscriptionTier.PREMIUM
        if (premium) {
            return AiAccessPolicy(SubscriptionTier.PREMIUM, properties.premiumDailySafetyLimit.toLong(), true, expiresAt)
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
