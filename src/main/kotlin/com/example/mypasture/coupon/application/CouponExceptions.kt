package com.example.mypasture.coupon.application

/** 모바일과 관리자가 안정적인 오류 코드로 분기할 수 있는 쿠폰 도메인 예외입니다. */
sealed class CouponException(message: String) : RuntimeException(message)
class CouponInvalidException : CouponException("유효한 쿠폰 코드를 확인해 주세요.")
class CouponNotStartedException : CouponException("아직 등록 기간이 시작되지 않은 쿠폰입니다.")
class CouponExpiredException : CouponException("등록 기간이 끝난 쿠폰입니다.")
class CouponExhaustedException : CouponException("모든 체험 수량이 사용된 쿠폰입니다.")
class CouponAlreadyRedeemedException : CouponException("이미 이 프리미엄 체험을 사용했습니다.")
class CouponCampaignNotFoundException : CouponException("쿠폰 캠페인을 찾을 수 없습니다.")
class CouponCampaignInvalidException(message: String) : CouponException(message)

