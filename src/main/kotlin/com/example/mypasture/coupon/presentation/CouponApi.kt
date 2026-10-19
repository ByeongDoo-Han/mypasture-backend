package com.example.mypasture.coupon.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations.Operation
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
@Tag(name = "쿠폰 · 보상", description = "사용자의 이벤트 보상 조회와 쿠폰 등록을 처리합니다.")
interface CouponApi {
    @Operation(summary = "내 쿠폰 보상 조회", description = "로그인한 사용자의 이벤트 보상과 수령 내역을 조회합니다.")
    @GetMapping("/rewards")
    fun rewards(@AuthenticationPrincipal principal: JwtPrincipal): List<UserCollectibleResponse>

    @Operation(summary = "쿠폰 코드 등록", description = "쿠폰 코드를 검증하고 보상을 계정에 지급합니다. 같은 쿠폰의 중복 지급은 허용하지 않습니다.")
    @PostMapping("/redeem")
    fun redeem(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Valid @RequestBody request: RedeemCouponRequest,
    ): CouponRedemptionResponse
}
