package com.example.mypasture.coupon.application

import com.example.mypasture.coupon.domain.CouponCampaignStatus
import com.example.mypasture.coupon.domain.CouponCodeType
import com.example.mypasture.coupon.domain.CouponRewardType
import java.time.Instant
import java.util.UUID

/** 이벤트 코드 등록으로 실제 지급된 수집 아이템 결과입니다. */
data class CouponRedemptionResult(
    val campaignName: String,
    val reward: CollectibleRewardView,
)

/** 한 번의 등록으로 지급된 수량과 등록 후 누적 수량을 함께 전달합니다. */
data class CollectibleRewardView(
    val id: UUID,
    val type: CouponRewardType,
    val itemCode: String,
    val quantity: Int,
    val grantedQuantity: Int,
    val updatedAt: Instant,
)

/** 사용자가 현재 보유한 목장 꾸미기 아이템 한 종류입니다. */
data class UserCollectibleView(
    val id: UUID,
    val itemCode: String,
    val quantity: Int,
    val updatedAt: Instant,
)

/** 관리자가 무료 이벤트 보상 캠페인을 생성할 때 사용하는 application command입니다. */
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
    val rewardType: CouponRewardType,
    val rewardItemCode: String,
    val rewardQuantity: Int,
    val reason: String,
)

/** 앱과 관리자 화면에 노출할 이벤트 보상 캠페인 상태입니다. */
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
    val rewardType: CouponRewardType,
    val rewardItemCode: String,
    val rewardQuantity: Int,
    val createdAt: Instant,
)

/** 생성된 캠페인과 최초 한 번만 노출되는 원문 코드를 묶은 결과입니다. */
data class CreatedCouponCampaign(
    val campaign: CouponCampaignView,
    val generatedCodes: List<String>,
)

/** 관리자 목록 화면의 페이지 정보와 캠페인 목록입니다. */
data class CouponCampaignPage(
    val items: List<CouponCampaignView>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)
