package com.example.mypasture.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** 쿠폰 원문을 저장하지 않고 조회 가능한 HMAC으로 변환할 때 쓰는 서버 전용 비밀값입니다. */
@ConfigurationProperties("app.coupon")
data class CouponProperties(
    val codePepper: String,
)
