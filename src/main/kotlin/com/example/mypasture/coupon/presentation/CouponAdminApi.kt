package com.example.mypasture.coupon.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.coupon.domain.CouponCampaignStatus
import com.example.mypasture.coupon.domain.CouponCodeType
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import java.time.Instant
import java.util.UUID

data class CreateCouponCampaignRequest(
    val operationId: UUID,
    @field:Size(min = 2, max = 100) val name: String,
    @field:Size(min = 2, max = 80) val eligibilityKey: String,
    val codeType: CouponCodeType,
    val redeemStartsAt: Instant,
    val redeemEndsAt: Instant,
    @field:Min(1) @field:Max(1_000_000) val maxRedemptions: Long? = null,
    @field:Min(1) @field:Max(1_000) val codeCount: Int? = null,
    @field:Size(min = 8, max = 40) val sharedCode: String? = null,
    @field:Min(1) @field:Max(90) val entitlementDurationDays: Int,
    @field:Min(1) @field:Max(500) val aiDailyLimit: Int,
    @field:Size(min = 5, max = 500) val reason: String,
)

data class DeactivateCouponCampaignRequest(
    val operationId: UUID,
    @field:Size(min = 5, max = 500) val reason: String,
)

data class CouponCampaignResponse(
    val id: UUID,
    val name: String,
    val eligibilityKey: String,
    val codeType: String,
    val status: String,
    val redeemStartsAt: Instant,
    val redeemEndsAt: Instant,
    val maxRedemptions: Long,
    val redeemedCount: Long,
    val entitlementDurationDays: Int,
    val aiDailyLimit: Int,
    val createdAt: Instant,
)

data class CreatedCouponCampaignResponse(val campaign: CouponCampaignResponse, val generatedCodes: List<String>)
data class CouponCampaignPageResponse(
    val items: List<CouponCampaignResponse>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)

/** Swagger 문서와 구현을 분리한 관리자 쿠폰 캠페인 API 계약입니다. */
interface CouponAdminApi {
    @GetMapping
    fun list(
        @RequestParam status: CouponCampaignStatus?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): CouponCampaignPageResponse

    @PostMapping
    fun create(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Valid @RequestBody request: CreateCouponCampaignRequest,
    ): CreatedCouponCampaignResponse

    @PostMapping("/{id}/deactivate")
    fun deactivate(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @PathVariable id: UUID,
        @Valid @RequestBody request: DeactivateCouponCampaignRequest,
    ): CouponCampaignResponse
}
