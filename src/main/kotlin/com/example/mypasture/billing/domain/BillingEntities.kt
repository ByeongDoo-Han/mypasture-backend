package com.example.mypasture.billing.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/** 구매별 권한 상태입니다. 환불 금전 상태와 서비스 접근 상태를 별도로 보관합니다. */
@Entity
@Table(name = "billing_purchases", uniqueConstraints = [
    UniqueConstraint(name = "uk_billing_purchase_store_transaction", columnNames = ["store", "environment", "transaction_key"]),
])
class BillingPurchaseEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(nullable = false) val userId: UUID,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) val store: BillingStore,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) val environment: BillingEnvironment,
    @Column(nullable = false, length = 200) val transactionKey: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 40) val product: BillingProduct,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) var financialStatus: BillingFinancialStatus,
    @Column(nullable = false) var accessRevoked: Boolean,
    var accessStartsAt: Instant?,
    var accessExpiresAt: Instant?,
    @Column(nullable = false) var autoRenew: Boolean,
    @Column(nullable = false) var entitledUnits: Int,
    @Column(nullable = false) var revision: Long,
    @Column(nullable = false) val createdAt: Instant = Instant.now(),
    @Column(nullable = false) var updatedAt: Instant = createdAt,
) {
    fun apply(snapshot: VerifiedPurchaseSnapshot) {
        financialStatus = snapshot.financialStatus
        accessRevoked = snapshot.accessRevoked
        accessStartsAt = snapshot.accessStartsAt
        accessExpiresAt = snapshot.accessExpiresAt
        autoRenew = snapshot.autoRenew
        entitledUnits = snapshot.entitledUnits
        revision = snapshot.revision
        updatedAt = Instant.now()
    }
}

/** 중복 알림과 역순 결과를 추적하는 변경 불가 원장입니다. 원문 영수증과 개인정보는 저장하지 않습니다. */
@Entity
@Table(name = "billing_purchase_events", uniqueConstraints = [
    UniqueConstraint(name = "uk_billing_event_purchase", columnNames = ["purchase_id", "event_key"]),
])
class BillingPurchaseEventEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(nullable = false) val purchaseId: UUID,
    @Column(nullable = false, length = 200) val eventKey: String,
    @Column(nullable = false, length = 64) val snapshotHash: String,
    @Column(nullable = false) val revision: Long,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) val financialStatus: BillingFinancialStatus,
    @Column(nullable = false) val accessRevoked: Boolean,
    val accessStartsAt: Instant?,
    val accessExpiresAt: Instant?,
    @Column(nullable = false) val autoRenew: Boolean,
    @Column(nullable = false) val entitledUnits: Int,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) val result: BillingApplyResult,
    @Column(nullable = false) val createdAt: Instant = Instant.now(),
)

/** 심층 상담 한 세션이 사용하는 구매 lot과 최종 상태를 기록해 중복 차감과 복원을 막습니다. */
@Entity
@Table(name = "billing_credit_reservations", uniqueConstraints = [
    UniqueConstraint(name = "uk_billing_credit_user_session", columnNames = ["user_id", "session_id"]),
])
class BillingCreditReservationEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(nullable = false) val userId: UUID,
    @Column(nullable = false) val sessionId: UUID,
    @Column(nullable = false) val purchaseId: UUID,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    var status: BillingReservationStatus = BillingReservationStatus.RESERVED,
    @Column(length = 40) var releaseReason: String? = null,
    @Column(nullable = false) val createdAt: Instant = Instant.now(),
    @Column(nullable = false) var updatedAt: Instant = createdAt,
) {
    fun release(reason: String) {
        if (status != BillingReservationStatus.RESERVED) return
        status = BillingReservationStatus.RELEASED
        releaseReason = reason
        updatedAt = Instant.now()
    }
}
