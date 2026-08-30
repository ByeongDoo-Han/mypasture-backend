package com.example.mypasture.coupon.application

import com.example.mypasture.coupon.domain.CouponCampaignStatus
import com.example.mypasture.coupon.domain.CouponCodeType
import com.example.mypasture.coupon.domain.EntitlementType
import java.time.Instant
import java.util.UUID

data class EntitlementView(
    val id: UUID,
    val type: EntitlementType,
    val startsAt: Instant,
    val expiresAt: Instant,
    val aiDailyLimit: Int?,
)

data class CouponRedemptionResult(
    val campaignName: String,
    val entitlement: EntitlementView,
)

data class CreateCouponCampaignCommand(
    val operationId: UUID,
    val name: String,
    val eligibilityKey: String,
    val codeType: CouponCodeType,
    val redeemStartsAt: Instant,
    val redeemEndsAt: Instant,
    val maxRedemptions: Long?,
    val codeCount: Int?,
    val sharedCode: String?,
    val entitlementDurationDays: Int,
    val aiDailyLimit: Int,
    val reason: String,
)

data class CouponCampaignView(
    val id: UUID,
    val name: String,
    val eligibilityKey: String,
    val codeType: CouponCodeType,
    val status: CouponCampaignStatus,
    val redeemStartsAt: Instant,
    val redeemEndsAt: Instant,
    val maxRedemptions: Long,
    val redeemedCount: Long,
    val entitlementDurationDays: Int,
    val aiDailyLimit: Int,
    val createdAt: Instant,
)

data class CreatedCouponCampaign(
    val campaign: CouponCampaignView,
    val generatedCodes: List<String>,
)

data class CouponCampaignPage(
    val items: List<CouponCampaignView>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)
