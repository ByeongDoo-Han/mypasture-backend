package com.example.mypasture.coupon.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.coupon.application.CouponRedemptionResult
import com.example.mypasture.coupon.application.CouponRedemptionService
import com.example.mypasture.coupon.application.EntitlementView
import com.example.mypasture.coupon.application.UserEntitlementService
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

data class EntitlementResponse(
    val id: UUID,
    val type: String,
    val startsAt: Instant,
    val expiresAt: Instant,
    val aiDailyLimit: Int?,
)

data class CouponRedemptionResponse(val campaignName: String, val entitlement: EntitlementResponse)
data class UserEntitlementsResponse(val entitlements: List<EntitlementResponse>)

/** 인증 사용자의 쿠폰 등록 요청을 application service에 전달합니다. */
@RestController
@RequestMapping("/api/v1/coupons")
class CouponController(private val service: CouponRedemptionService) : CouponApi {
    override fun redeem(principal: JwtPrincipal, request: RedeemCouponRequest) = service.redeem(principal.userId, request.code).toResponse()
}

/** 모바일이 현재 유효한 체험 권한과 만료 시각을 조회하는 endpoint입니다. */
@RestController
@RequestMapping("/api/v1/entitlements")
class EntitlementController(private val service: UserEntitlementService) : EntitlementApi {
    override fun me(principal: JwtPrincipal) = UserEntitlementsResponse(service.current(principal.userId).map { it.toResponse() })
}

private fun CouponRedemptionResult.toResponse() = CouponRedemptionResponse(campaignName, entitlement.toResponse())
private fun EntitlementView.toResponse() = EntitlementResponse(id, type.name, startsAt, expiresAt, aiDailyLimit)
