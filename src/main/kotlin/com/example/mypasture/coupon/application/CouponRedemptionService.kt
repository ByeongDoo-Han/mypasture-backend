package com.example.mypasture.coupon.application

import com.example.mypasture.auth.infrastructure.UserRepository
import com.example.mypasture.coupon.domain.CouponCampaignStatus
import com.example.mypasture.coupon.domain.CouponRedemptionEntity
import com.example.mypasture.coupon.domain.UserCollectibleEntity
import com.example.mypasture.coupon.infrastructure.CouponCampaignRepository
import com.example.mypasture.coupon.infrastructure.CouponCodeHasher
import com.example.mypasture.coupon.infrastructure.CouponCodeRepository
import com.example.mypasture.coupon.infrastructure.CouponRedemptionRepository
import com.example.mypasture.coupon.infrastructure.UserCollectibleRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/** 이벤트 코드 등록과 비구매 꾸미기 보상 지급을 한 트랜잭션으로 처리합니다. */
@Service
class CouponRedemptionService(
    private val hasher: CouponCodeHasher,
    private val campaigns: CouponCampaignRepository,
    private val codes: CouponCodeRepository,
    private val redemptions: CouponRedemptionRepository,
    private val collectibles: UserCollectibleRepository,
    private val users: UserRepository,
) {
    @Transactional(readOnly = true)
    fun collectibles(userId: UUID): List<UserCollectibleView> =
        collectibles.findAllByUserIdOrderByUpdatedAtDesc(userId).map {
            UserCollectibleView(it.id, it.itemCode, it.quantity, it.updatedAt)
        }

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

        val collectible = collectibles.findByUserIdAndItemCode(userId, campaign.rewardItemCode)
            ?: UserCollectibleEntity(
                userId = userId,
                itemCode = campaign.rewardItemCode,
                quantity = 0,
                firstGrantedAt = now,
                updatedAt = now,
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
            collectible.grant(campaign.rewardQuantity, now)
            collectibles.save(collectible)
            campaign.redeem(now)
            code.redeem()
        } catch (_: DataIntegrityViolationException) {
            throw CouponAlreadyRedeemedException()
        }
        return CouponRedemptionResult(
            campaignName = campaign.name,
            reward = CollectibleRewardView(
                id = collectible.id,
                type = campaign.rewardType,
                itemCode = collectible.itemCode,
                quantity = collectible.quantity,
                grantedQuantity = campaign.rewardQuantity,
                updatedAt = collectible.updatedAt,
            ),
        )
    }
}
