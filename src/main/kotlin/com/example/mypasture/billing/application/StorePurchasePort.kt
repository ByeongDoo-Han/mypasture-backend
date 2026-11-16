package com.example.mypasture.billing.application

import com.example.mypasture.billing.domain.*
import java.time.Instant
import java.util.UUID

/** 외부 구매 조회를 도메인의 검증 결과로 변환합니다. 구현체가 원본 증거를 검증해야 합니다. */
interface StorePurchasePort {
    val store: BillingStore
    fun verify(productId: String, proof: String): VerifiedStorePurchase
    fun acknowledge(productId: String, proof: String)
}

/** 스토어가 돌려준 실제 소유자와 최신 권한 상태이며 userId를 클라이언트에서 받지 않습니다. */
data class VerifiedStorePurchase(
    val ownerToken: UUID,
    val transactionKey: String,
    val sourceKey: String,
    val replacesSourceKey: String? = null,
    val product: BillingProduct,
    val financialStatus: BillingFinancialStatus,
    val revoked: Boolean,
    val startsAt: Instant?,
    val expiresAt: Instant?,
    val autoRenew: Boolean,
    val units: Int,
    val needsAcknowledgement: Boolean = false,
)

/** 사용자에게 원본 스토어 오류/토큰을 노출하지 않는 검증 실패입니다. */
class StorePurchaseRejected(val reason: String) : RuntimeException(reason)
class StoreTemporarilyUnavailable : RuntimeException("STORE_TEMPORARILY_UNAVAILABLE")
class BillingNotEnabled : RuntimeException("BILLING_NOT_ENABLED")
class BillingRequestNotFound : RuntimeException("BILLING_REQUEST_NOT_FOUND")

/** 스토어 거래 계열을 토큰 원문 없이 식별합니다. */
fun billingSourceKey(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
