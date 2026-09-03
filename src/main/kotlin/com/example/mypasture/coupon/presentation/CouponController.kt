package com.example.mypasture.coupon.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.coupon.application.CouponRedemptionResult
import com.example.mypasture.coupon.application.CouponRedemptionService
import com.example.mypasture.coupon.application.CollectibleRewardView
import com.example.mypasture.coupon.application.UserCollectibleView
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

data class CollectibleRewardResponse(
    val id: UUID,
    val type: String,
    val itemCode: String,
    val quantity: Int,
    val grantedQuantity: Int,
    val updatedAt: Instant,
)

data class CouponRedemptionResponse(val campaignName: String, val reward: CollectibleRewardResponse)
data class UserCollectibleResponse(val id: UUID, val itemCode: String, val quantity: Int, val updatedAt: Instant)

/** 인증 사용자의 쿠폰 등록 요청을 application service에 전달합니다. */
@RestController
@RequestMapping("/api/v1/coupons")
class CouponController(private val service: CouponRedemptionService) : CouponApi {
    override fun rewards(principal: JwtPrincipal) = service.collectibles(principal.userId).map { it.toResponse() }

    override fun redeem(principal: JwtPrincipal, request: RedeemCouponRequest) = service.redeem(principal.userId, request.code).toResponse()
}

private fun CouponRedemptionResult.toResponse() = CouponRedemptionResponse(campaignName, reward.toResponse())
private fun CollectibleRewardView.toResponse() = CollectibleRewardResponse(id, type.name, itemCode, quantity, grantedQuantity, updatedAt)
private fun UserCollectibleView.toResponse() = UserCollectibleResponse(id, itemCode, quantity, updatedAt)
