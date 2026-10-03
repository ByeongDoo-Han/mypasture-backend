package com.example.mypasture.billing.domain

import java.time.Instant
import java.util.UUID

/** 스토어와 테스트 결제를 운영 결제와 구분하는 원장 식별자입니다. */
enum class BillingStore { APPLE, GOOGLE }
enum class BillingEnvironment { SANDBOX, PRODUCTION }
enum class BillingProduct(val units: Int) { MONTHLY(0), YEARLY(0), DEEP_THREE_SESSIONS(3) }
enum class BillingFinancialStatus { PAID, PARTIALLY_REFUNDED, REFUNDED }
enum class BillingReservationStatus { RESERVED, COMPLETED, RELEASED }
enum class BillingApplyResult { APPLIED, DUPLICATE, STALE }

/**
 * 신뢰된 스토어 검증 어댑터만 생성해야 하는 구매 한 건의 최신 상태입니다. 검증 증명 자체는 아닙니다.
 * revision은 구매별 조회를 직렬화한 동기화 작업의 증가 번호이며 알림 도착 시각이 아닙니다.
 * transactionKey는 갱신 회차별 거래 ID입니다. Google purchaseToken을 이 필드에 저장하지 않습니다.
 */
data class VerifiedPurchaseSnapshot(
    val userId: UUID,
    val store: BillingStore,
    val environment: BillingEnvironment,
    val transactionKey: String,
    val eventKey: String,
    val revision: Long,
    val product: BillingProduct,
    val financialStatus: BillingFinancialStatus,
    val accessRevoked: Boolean,
    val accessStartsAt: Instant?,
    val accessExpiresAt: Instant?,
    val autoRenew: Boolean,
    val entitledUnits: Int,
    val sourceKey: String? = null,
) {
    init {
        require(transactionKey.isNotBlank() && transactionKey.length <= 200)
        require(eventKey.isNotBlank() && eventKey.length <= 200)
        require(revision > 0)
        require(sourceKey == null || sourceKey.matches(Regex("[0-9a-f]{64}")))
        require(entitledUnits in 0..product.units)
        if (product.units == 0) {
            require(accessStartsAt != null && accessExpiresAt != null && accessExpiresAt.isAfter(accessStartsAt))
        } else {
            require(accessStartsAt == null && accessExpiresAt == null && !autoRenew)
        }
        require(!accessRevoked || entitledUnits == 0)
    }
}

/** 구매 소유권이나 이벤트 식별자가 서로 다른 검증 결과로 재사용된 경우의 충돌입니다. */
class BillingConflictException : RuntimeException("결제 원장 식별자 또는 소유권이 일치하지 않습니다.")

/** 부족한 이용권으로 AI 세션을 예약하려는 요청입니다. */
class BillingCreditUnavailableException : RuntimeException("사용 가능한 심층 상담 이용권이 없습니다.")

/** 아직 구매 권한을 확정하지 않았거나 이미 종료한 예약을 잘못 사용한 경우입니다. */
class BillingReservationException : RuntimeException("이용권 예약 상태를 확인할 수 없습니다.")
