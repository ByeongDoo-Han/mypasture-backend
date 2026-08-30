package com.example.mypasture.coupon.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.coupon.application.CouponCampaignAdminService
import com.example.mypasture.coupon.application.CouponCampaignPage
import com.example.mypasture.coupon.application.CouponCampaignView
import com.example.mypasture.coupon.application.CreateCouponCampaignCommand
import com.example.mypasture.coupon.domain.CouponCampaignStatus
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** 관리자 HTTP 요청을 쿠폰 캠페인 발급·중지 유스케이스에 전달합니다. */
@RestController
@RequestMapping("/api/v1/admin/coupon-campaigns")
class CouponAdminController(private val service: CouponCampaignAdminService) : CouponAdminApi {
    override fun list(status: CouponCampaignStatus?, page: Int, size: Int) = service.list(status, page, size).toResponse()

    override fun create(principal: JwtPrincipal, request: CreateCouponCampaignRequest): CreatedCouponCampaignResponse {
        val created = service.create(
            principal.userId,
            CreateCouponCampaignCommand(
                request.operationId, request.name, request.eligibilityKey, request.codeType,
                request.redeemStartsAt, request.redeemEndsAt, request.maxRedemptions, request.codeCount,
                request.sharedCode, request.entitlementDurationDays, request.aiDailyLimit, request.reason,
            ),
        )
        return CreatedCouponCampaignResponse(created.campaign.toResponse(), created.generatedCodes)
    }

    override fun deactivate(principal: JwtPrincipal, id: UUID, request: DeactivateCouponCampaignRequest) =
        service.deactivate(id, principal.userId, request.operationId, request.reason).toResponse()
}

private fun CouponCampaignPage.toResponse() = CouponCampaignPageResponse(items.map { it.toResponse() }, page, size, totalElements, totalPages)
private fun CouponCampaignView.toResponse() = CouponCampaignResponse(
    id, name, eligibilityKey, codeType.name, status.name, redeemStartsAt, redeemEndsAt,
    maxRedemptions, redeemedCount, entitlementDurationDays, aiDailyLimit, createdAt,
)
