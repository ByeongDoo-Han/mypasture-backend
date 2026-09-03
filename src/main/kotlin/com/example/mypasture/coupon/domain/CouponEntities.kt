package com.example.mypasture.coupon.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

enum class CouponCodeType { SHARED, UNIQUE }
enum class CouponCampaignStatus { ACTIVE, INACTIVE }
enum class CouponRewardType { PASTURE_DECORATION_ITEM }

/** 이벤트 코드 등록 기간, 전체 수량과 지급할 비구매 꾸미기 보상을 보관하는 캠페인입니다. */
@Entity
@Table(name = "coupon_campaigns")
class CouponCampaignEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(length = 100) val name: String,
    @Column(length = 80) val eligibilityKey: String,
    @Enumerated(EnumType.STRING) val codeType: CouponCodeType,
    @Enumerated(EnumType.STRING) var status: CouponCampaignStatus = CouponCampaignStatus.ACTIVE,
    val redeemStartsAt: Instant,
    val redeemEndsAt: Instant,
    val maxRedemptions: Long,
    var redeemedCount: Long = 0,
    val entitlementDurationDays: Int? = null,
    val aiDailyLimit: Int? = null,
    @Enumerated(EnumType.STRING)
    val rewardType: CouponRewardType,
    @Column(length = 80)
    val rewardItemCode: String,
    val rewardQuantity: Int,
    val createdBy: UUID,
    val createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
) {
    fun redeem(now: Instant) {
        redeemedCount += 1
        updatedAt = now
    }

    fun deactivate(now: Instant) {
        status = CouponCampaignStatus.INACTIVE
        updatedAt = now
    }
}

/** 쿠폰 원문 대신 HMAC과 일부 마스킹 힌트만 저장하는 발급 코드입니다. */
@Entity
@Table(name = "coupon_codes")
class CouponCodeEntity(
    @Id val id: UUID = UUID.randomUUID(),
    val campaignId: UUID,
    @Column(length = 64, unique = true) val codeHash: String,
    @Column(length = 20) val codeHint: String,
    val maxRedemptions: Long,
    var redeemedCount: Long = 0,
    val createdAt: Instant = Instant.now(),
) {
    fun redeem() { redeemedCount += 1 }
}

/** 한 사용자가 같은 이벤트 보상을 여러 코드로 반복 획득하지 못하게 하는 영구 등록 이력입니다. */
@Entity
@Table(name = "coupon_redemptions")
class CouponRedemptionEntity(
    @Id val id: UUID = UUID.randomUUID(),
    val campaignId: UUID,
    val couponCodeId: UUID,
    val userId: UUID,
    @Column(length = 80) val eligibilityKey: String,
    val redeemedAt: Instant = Instant.now(),
)

/** 사용자가 이벤트 코드로 받은 목장 꾸미기 아이템의 누적 보유량입니다. */
@Entity
@Table(name = "user_collectibles")
class UserCollectibleEntity(
    @Id val id: UUID = UUID.randomUUID(),
    val userId: UUID,
    @Column(length = 80) val itemCode: String,
    var quantity: Int,
    val firstGrantedAt: Instant = Instant.now(),
    var updatedAt: Instant = firstGrantedAt,
) {
    fun grant(quantity: Int, now: Instant) {
        this.quantity += quantity
        updatedAt = now
    }
}
