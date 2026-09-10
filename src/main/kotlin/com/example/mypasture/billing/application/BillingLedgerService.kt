package com.example.mypasture.billing.application

import com.example.mypasture.auth.infrastructure.UserRepository
import com.example.mypasture.billing.domain.*
import com.example.mypasture.billing.infrastructure.*
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * 스토어 검증 이후의 내부 원장 경계입니다. HTTP에 직접 노출하거나 클라이언트 본문을 바인딩하지 않습니다.
 * 모든 변경은 사용자 행부터 잠그며 이벤트, 구매, 이용권을 하나의 DB 트랜잭션으로 갱신합니다.
 */
@Service
class BillingLedgerService(
    private val users: UserRepository,
    private val purchases: BillingPurchaseRepository,
    private val events: BillingPurchaseEventRepository,
    private val reservations: BillingCreditReservationRepository,
    private val objectMapper: ObjectMapper,
) {
    @Transactional
    fun applyVerifiedPurchase(snapshot: VerifiedPurchaseSnapshot): BillingApplyResult {
        val owner = users.findIncludingWithdrawnByIdForUpdate(snapshot.userId) ?: throw BillingConflictException()
        val digest = MessageDigest.getInstance("SHA-256").digest(objectMapper.writeValueAsBytes(snapshot))
            .joinToString("") { "%02x".format(it) }
        var purchase = purchases.findByStoreAndEnvironmentAndTransactionKey(snapshot.store, snapshot.environment, snapshot.transactionKey)
        if (owner.deletedAt != null && purchase == null) throw BillingConflictException()
        if (purchase != null) {
            if (purchase.userId != snapshot.userId || purchase.product != snapshot.product) throw BillingConflictException()
            events.findByPurchaseIdAndEventKey(purchase.id, snapshot.eventKey)?.let {
                if (it.snapshotHash != digest) throw BillingConflictException()
                return BillingApplyResult.DUPLICATE
            }
        }
        val result = if (purchase != null && snapshot.revision <= purchase.revision) BillingApplyResult.STALE else BillingApplyResult.APPLIED
        if (purchase == null) {
            purchase = purchases.save(BillingPurchaseEntity(
                userId = snapshot.userId, store = snapshot.store, environment = snapshot.environment,
                transactionKey = snapshot.transactionKey, product = snapshot.product, financialStatus = snapshot.financialStatus,
                accessRevoked = snapshot.accessRevoked, accessStartsAt = snapshot.accessStartsAt,
                accessExpiresAt = snapshot.accessExpiresAt, autoRenew = snapshot.autoRenew,
                entitledUnits = snapshot.entitledUnits, revision = snapshot.revision,
            ))
        } else if (result == BillingApplyResult.APPLIED) {
            // 부분 환불 중 진행 중인 세션은 해당 lot에서만 해제합니다. 완료 차감은 그대로 남깁니다.
            if (snapshot.accessRevoked || snapshot.entitledUnits < purchase.entitledUnits) {
                reservations.findAllByPurchaseIdAndStatus(purchase.id, BillingReservationStatus.RESERVED)
                    .forEach { it.release("PURCHASE_ENTITLEMENT_REDUCED") }
            }
            purchase.apply(snapshot)
        }
        events.save(BillingPurchaseEventEntity(
            purchaseId = purchase.id, eventKey = snapshot.eventKey, snapshotHash = digest,
            revision = snapshot.revision, financialStatus = snapshot.financialStatus,
            accessRevoked = snapshot.accessRevoked, accessStartsAt = snapshot.accessStartsAt,
            accessExpiresAt = snapshot.accessExpiresAt, autoRenew = snapshot.autoRenew,
            entitledUnits = snapshot.entitledUnits, result = result,
        ))
        return result
    }

    @Transactional(readOnly = true)
    fun premiumExpiresAt(userId: UUID, environment: BillingEnvironment): Instant? {
        if (users.findByIdAndDeletedAtIsNull(userId) == null) return null
        return purchases.premiumExpiresAt(userId, environment, Instant.now())
    }

    /** 같은 sessionId는 종료 후에도 재예약하지 않습니다. 후속 질문은 최초 예약을 재사용해야 합니다. */
    @Transactional
    fun reserveCredit(userId: UUID, sessionId: UUID, environment: BillingEnvironment): BillingCreditReservationView {
        lockUser(userId)
        reservations.findByUserIdAndSessionId(userId, sessionId)?.let {
            val purchase = purchases.findById(it.purchaseId).orElseThrow { BillingConflictException() }
            if (purchase.environment != environment) throw BillingConflictException()
            return it.view()
        }
        val lot = purchases.availableLots(userId, environment, COUNTED, PageRequest.of(0, 1)).firstOrNull()
            ?: throw BillingCreditUnavailableException()
        return reservations.save(BillingCreditReservationEntity(userId = userId, sessionId = sessionId, purchaseId = lot.id)).view()
    }

    /** 최종 응답 저장과 같은 트랜잭션에서 호출해야 응답 없이 차감되는 일을 막을 수 있습니다. */
    @Transactional
    fun completeCredit(userId: UUID, sessionId: UUID): BillingCreditReservationView {
        val reservation = lockedReservation(userId, sessionId)
        if (reservation.status == BillingReservationStatus.RELEASED) throw BillingReservationException()
        if (reservation.status == BillingReservationStatus.COMPLETED) return reservation.view()
        reservation.status = BillingReservationStatus.COMPLETED
        reservation.updatedAt = Instant.now()
        return reservation.view()
    }

    @Transactional
    fun releaseCredit(userId: UUID, sessionId: UUID): BillingCreditReservationView {
        val reservation = lockedReservation(userId, sessionId)
        reservation.release("AI_FAILED")
        return reservation.view()
    }

    private fun lockUser(userId: UUID) {
        users.findActiveByIdForUpdate(userId) ?: throw BillingConflictException()
    }

    private fun lockedReservation(userId: UUID, sessionId: UUID): BillingCreditReservationEntity {
        lockUser(userId)
        return reservations.findByUserIdAndSessionId(userId, sessionId) ?: throw BillingReservationException()
    }

    private fun BillingCreditReservationEntity.view() = BillingCreditReservationView(id, sessionId, status)

    companion object {
        private val COUNTED = listOf(BillingReservationStatus.RESERVED, BillingReservationStatus.COMPLETED)
    }
}

/** 호출자가 종료된 예약을 재사용하지 않도록 상태를 포함한 불변 결과를 반환합니다. */
data class BillingCreditReservationView(val id: UUID, val sessionId: UUID, val status: BillingReservationStatus)
