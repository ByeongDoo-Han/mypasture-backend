package com.example.mypasture.coupon.presentation

import com.example.mypasture.common.error.ApiErrorResponse
import com.example.mypasture.common.error.ErrorCode
import com.example.mypasture.coupon.application.CouponAlreadyRedeemedException
import com.example.mypasture.coupon.application.CouponCampaignInvalidException
import com.example.mypasture.coupon.application.CouponCampaignNotFoundException
import com.example.mypasture.coupon.application.CouponExhaustedException
import com.example.mypasture.coupon.application.CouponExpiredException
import com.example.mypasture.coupon.application.CouponInvalidException
import com.example.mypasture.coupon.application.CouponNotStartedException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

enum class CouponErrorCode(override val code: String, override val defaultMessage: String) : ErrorCode {
    INVALID("COUPON_INVALID", "유효한 쿠폰 코드를 확인해 주세요."),
    NOT_STARTED("COUPON_NOT_STARTED", "아직 등록 기간이 시작되지 않은 쿠폰입니다."),
    EXPIRED("COUPON_EXPIRED", "등록 기간이 끝난 쿠폰입니다."),
    EXHAUSTED("COUPON_EXHAUSTED", "모든 체험 수량이 사용된 쿠폰입니다."),
    ALREADY_REDEEMED("COUPON_ALREADY_REDEEMED", "이미 이 프리미엄 체험을 사용했습니다."),
    CAMPAIGN_NOT_FOUND("COUPON_CAMPAIGN_NOT_FOUND", "쿠폰 캠페인을 찾을 수 없습니다."),
    CAMPAIGN_INVALID("COUPON_CAMPAIGN_INVALID", "쿠폰 캠페인 설정을 확인해 주세요."),
}

/** 쿠폰 정책 위반을 앱과 어드민이 분기 가능한 공통 오류 응답으로 변환합니다. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class CouponExceptionHandler {
    @ExceptionHandler(CouponInvalidException::class)
    fun invalid(error: CouponInvalidException, request: HttpServletRequest) = response(HttpStatus.BAD_REQUEST, CouponErrorCode.INVALID, error, request)
    @ExceptionHandler(CouponNotStartedException::class)
    fun notStarted(error: CouponNotStartedException, request: HttpServletRequest) = response(HttpStatus.CONFLICT, CouponErrorCode.NOT_STARTED, error, request)
    @ExceptionHandler(CouponExpiredException::class)
    fun expired(error: CouponExpiredException, request: HttpServletRequest) = response(HttpStatus.GONE, CouponErrorCode.EXPIRED, error, request)
    @ExceptionHandler(CouponExhaustedException::class)
    fun exhausted(error: CouponExhaustedException, request: HttpServletRequest) = response(HttpStatus.CONFLICT, CouponErrorCode.EXHAUSTED, error, request)
    @ExceptionHandler(CouponAlreadyRedeemedException::class)
    fun duplicated(error: CouponAlreadyRedeemedException, request: HttpServletRequest) = response(HttpStatus.CONFLICT, CouponErrorCode.ALREADY_REDEEMED, error, request)
    @ExceptionHandler(CouponCampaignNotFoundException::class)
    fun notFound(error: CouponCampaignNotFoundException, request: HttpServletRequest) = response(HttpStatus.NOT_FOUND, CouponErrorCode.CAMPAIGN_NOT_FOUND, error, request)
    @ExceptionHandler(CouponCampaignInvalidException::class)
    fun campaignInvalid(error: CouponCampaignInvalidException, request: HttpServletRequest) = response(HttpStatus.BAD_REQUEST, CouponErrorCode.CAMPAIGN_INVALID, error, request)

    private fun response(status: HttpStatus, code: CouponErrorCode, error: RuntimeException, request: HttpServletRequest) =
        ResponseEntity.status(status).body(ApiErrorResponse.of(code, request, error.message ?: code.defaultMessage))
}
