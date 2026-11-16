package com.example.mypasture.billing.infrastructure

import com.example.mypasture.billing.domain.*
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

/** 결제별 권한과 잔여 lot을 조회합니다. 변경은 application의 사용자 행 잠금 안에서만 수행합니다. */
interface BillingPurchaseRepository : JpaRepository<BillingPurchaseEntity, UUID> {
    fun findAllByStoreAndEnvironmentAndSourceKey(store: BillingStore, environment: BillingEnvironment, sourceKey: String): List<BillingPurchaseEntity>

    @Query(value = """
        select coalesce(sum(greatest(0, p.entitled_units - coalesce(r.used, 0))), 0)
        from billing_purchases p left join (
          select purchase_id, count(*) used from billing_credit_reservations
          where user_id = :userId and status in ('RESERVED', 'COMPLETED') group by purchase_id
        ) r on r.purchase_id = p.id
        where p.user_id = :userId and p.environment = :environment and p.access_revoked = false
    """, nativeQuery = true)
    fun availableCredits(userId: UUID, environment: String): Long

    fun findByStoreAndEnvironmentAndTransactionKey(
        store: BillingStore, environment: BillingEnvironment, transactionKey: String,
    ): BillingPurchaseEntity?

    @Query("""
        select p from BillingPurchaseEntity p where p.userId = :userId and p.environment = :environment
        and p.accessRevoked = false and p.entitledUnits >
        (select count(r) from BillingCreditReservationEntity r where r.purchaseId = p.id and r.status in :counted)
        order by p.createdAt, p.id
    """)
    fun availableLots(userId: UUID, environment: BillingEnvironment, counted: Collection<BillingReservationStatus>, page: Pageable): List<BillingPurchaseEntity>

    @Query("""
        select max(p.accessExpiresAt) from BillingPurchaseEntity p
        where p.userId = :userId and p.environment = :environment and p.accessRevoked = false
        and p.accessStartsAt <= :now and p.accessExpiresAt > :now
    """)
    fun premiumExpiresAt(userId: UUID, environment: BillingEnvironment, now: Instant): Instant?
}

/** 처리한 이벤트의 본문 지문까지 비교하여 동일 ID를 이용한 다른 상태 덮어쓰기를 차단합니다. */
interface BillingPurchaseEventRepository : JpaRepository<BillingPurchaseEventEntity, UUID> {
    fun findByPurchaseIdAndEventKey(purchaseId: UUID, eventKey: String): BillingPurchaseEventEntity?
}

/** 이용권 차감 이력을 구매별로 유지하므로 환불 취소 시 이미 사용한 이용권이 재지급되지 않습니다. */
interface BillingCreditReservationRepository : JpaRepository<BillingCreditReservationEntity, UUID> {
    fun findByUserIdAndSessionId(userId: UUID, sessionId: UUID): BillingCreditReservationEntity?
    fun findAllByPurchaseIdAndStatus(purchaseId: UUID, status: BillingReservationStatus): List<BillingCreditReservationEntity>
}
