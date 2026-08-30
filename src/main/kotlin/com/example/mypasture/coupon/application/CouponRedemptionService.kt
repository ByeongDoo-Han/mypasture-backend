package com.example.mypasture.coupon.application

import com.example.mypasture.auth.infrastructure.UserRepository
import com.example.mypasture.coupon.domain.CouponCampaignStatus
import com.example.mypasture.coupon.domain.CouponRedemptionEntity
import com.example.mypasture.coupon.domain.EntitlementSourceType
import com.example.mypasture.coupon.domain.EntitlementType
import com.example.mypasture.coupon.domain.UserEntitlementEntity
import com.example.mypasture.coupon.infrastructure.CouponCampaignRepository
import com.example.mypasture.coupon.infrastructure.CouponCodeHasher
import com.example.mypasture.coupon.infrastructure.CouponCodeRepository
import com.example.mypasture.coupon.infrastructure.CouponRedemptionRepository
import com.example.mypasture.coupon.infrastructure.UserEntitlementRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** 쿠폰 등록과 체험 권한 지급을 한 트랜잭션으로 처리하고 중복·초과 지급을 차단합니다. */
@Service
class CouponRedemptionService(
    private val hasher: CouponCodeHasher,
    private val campaigns: CouponCampaignRepository,
    private val codes: CouponCodeRepository,
    private val redemptions: CouponRedemptionRepository,
    private val entitlements: UserEntitlementRepository,
    private val users: UserRepository,
) {
    @Transactional
    fun redeem(userId: UUID, rawCode: String, now: Instant = Instant.now()): CouponRedemptionResult {
        val codeHash = runCatching { hasher.hash(rawCode) }.getOrElse { throw CouponInvalidException() }
        val codeSnapshot = codes.findByCodeHash(codeHash) ?: throw CouponInvalidException()

        // 동일 사용자의 서로 다른 코드 동시 요청도 직렬화한 뒤 영구 unique constraint로 한 번 더 방어합니다.
        users.findActiveByIdForUpdate(userId) ?: throw CouponInvalidException()
        val campaign = campaigns.findByIdForUpdate(codeSnapshot.campaignId) ?: throw CouponInvalidException()
        val code = codes.findByIdForUpdate(codeSnapshot.id) ?: throw CouponInvalidException()

        if (campaign.status != CouponCampaignStatus.ACTIVE) throw CouponExpiredException()
        if (now.isBefore(campaign.redeemStartsAt)) throw CouponNotStartedException()
        if (!now.isBefore(campaign.redeemEndsAt)) throw CouponExpiredException()
        if (campaign.redeemedCount >= campaign.maxRedemptions || code.redeemedCount >= code.maxRedemptions) {
            throw CouponExhaustedException()
        }
        if (redemptions.existsByUserIdAndEligibilityKey(userId, campaign.eligibilityKey)) {
            throw CouponAlreadyRedeemedException()
        }

        val entitlement = UserEntitlementEntity(
            userId = userId,
            entitlementType = EntitlementType.PREMIUM_AI_TRIAL,
            sourceType = EntitlementSourceType.COUPON,
            sourceId = campaign.id,
            startsAt = now,
            expiresAt = now.plus(campaign.entitlementDurationDays.toLong(), ChronoUnit.DAYS),
            aiDailyLimit = campaign.aiDailyLimit,
        )
        try {
            redemptions.saveAndFlush(
                CouponRedemptionEntity(
                    campaignId = campaign.id,
                    couponCodeId = code.id,
                    userId = userId,
                    eligibilityKey = campaign.eligibilityKey,
                    redeemedAt = now,
                ),
            )
            entitlements.save(entitlement)
            campaign.redeem(now)
            code.redeem()
        } catch (_: DataIntegrityViolationException) {
            throw CouponAlreadyRedeemedException()
        }
        return CouponRedemptionResult(campaign.name, entitlement.toView())
    }
}
