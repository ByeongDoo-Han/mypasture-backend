package com.example.mypasture.coupon.infrastructure

import com.example.mypasture.config.CouponProperties
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** 사용자가 입력한 쿠폰을 동일한 형식으로 정규화하고 HMAC-SHA256 조회 키로 변환합니다. */
@Component
class CouponCodeHasher(private val properties: CouponProperties) {
    fun normalize(raw: String): String = raw.uppercase().filter(Char::isLetterOrDigit)

    fun hash(raw: String): String {
        val normalized = normalize(raw)
        require(normalized.length in 8..32) { "Coupon code must contain 8 to 32 letters or digits" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(properties.codePepper.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(normalized.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun hint(raw: String): String {
        val normalized = normalize(raw)
        return "${normalized.take(4)}-****-${normalized.takeLast(4)}"
    }
}
