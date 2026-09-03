package com.example.mypasture.coupon.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody

data class RedeemCouponRequest(
    @field:Size(min = 8, max = 40)
    @field:Pattern(regexp = "^[A-Za-z0-9 -]+$")
    val code: String,
)

/** Swagger 문서와 controller 구현을 분리한 사용자 이벤트 보상 API 계약입니다. */
interface CouponApi {
    @GetMapping("/rewards")
    fun rewards(@AuthenticationPrincipal principal: JwtPrincipal): List<UserCollectibleResponse>

    @PostMapping("/redeem")
    fun redeem(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Valid @RequestBody request: RedeemCouponRequest,
    ): CouponRedemptionResponse
}
