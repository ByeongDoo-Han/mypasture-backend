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
enum class EntitlementType { PREMIUM_AI_TRIAL }
enum class EntitlementSourceType { COUPON }

/** 쿠폰 등록 기간, 전체 수량과 지급할 AI 체험 정책을 보관하는 캠페인입니다. */
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
    val entitlementDurationDays: Int,
    val aiDailyLimit: Int,
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

/** 한 사용자가 같은 체험 자격을 여러 쿠폰으로 반복 획득하지 못하게 하는 영구 등록 이력입니다. */
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

/** 구독 등급을 변경하지 않고 기간이 지나면 자동으로 사라지는 사용자 기능 권한입니다. */
@Entity
@Table(name = "user_entitlements")
class UserEntitlementEntity(
    @Id val id: UUID = UUID.randomUUID(),
    val userId: UUID,
    @Enumerated(EnumType.STRING) val entitlementType: EntitlementType,
    @Enumerated(EnumType.STRING) val sourceType: EntitlementSourceType,
    val sourceId: UUID,
    val startsAt: Instant,
    val expiresAt: Instant,
    val aiDailyLimit: Int?,
    var revokedAt: Instant? = null,
    val createdAt: Instant = Instant.now(),
)
