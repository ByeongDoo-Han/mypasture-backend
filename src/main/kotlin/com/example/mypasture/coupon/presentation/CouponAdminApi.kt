package com.example.mypasture.coupon.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.coupon.domain.CouponCampaignStatus
import com.example.mypasture.coupon.domain.CouponCodeType
import com.example.mypasture.coupon.domain.CouponRewardType
import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
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
    val rewardType: CouponRewardType = CouponRewardType.PASTURE_DECORATION_ITEM,
    @field:Size(min = 2, max = 80) val rewardItemCode: String,
    @field:Min(1) @field:Max(100) val rewardQuantity: Int,
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
    val rewardType: String,
    val rewardItemCode: String,
    val rewardQuantity: Int,
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
@Tag(name = "관리자 · 쿠폰", description = "쿠폰 캠페인의 생성·조회·중지를 관리합니다.")
interface CouponAdminApi {
    @Operation(summary = "쿠폰 캠페인 목록", description = "관리자가 캠페인 상태별 목록을 페이지로 조회합니다.")
    @GetMapping
    fun list(
        @Parameter(description = "결과를 제한할 처리 상태.") @RequestParam status: CouponCampaignStatus?,
        @Parameter(description = "0부터 시작하는 페이지 번호.") @RequestParam(defaultValue = "0") page: Int,
        @Parameter(description = "한 번에 반환할 최대 항목 수.") @RequestParam(defaultValue = "20") size: Int,
    ): CouponCampaignPageResponse

    @Operation(summary = "쿠폰 캠페인 생성", description = "관리자가 보상과 발급 조건을 지정해 쿠폰 캠페인을 생성합니다.")
    @PostMapping
    fun create(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Valid @RequestBody request: CreateCouponCampaignRequest,
    ): CreatedCouponCampaignResponse

    @Operation(summary = "쿠폰 캠페인 비활성화", description = "관리자가 캠페인을 중지하고 사유를 기록합니다.")
    @PostMapping("/{id}/deactivate")
    fun deactivate(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Parameter(description = "비활성화할 쿠폰 캠페인 UUID.") @PathVariable id: UUID,
        @Valid @RequestBody request: DeactivateCouponCampaignRequest,
    ): CouponCampaignResponse
}
