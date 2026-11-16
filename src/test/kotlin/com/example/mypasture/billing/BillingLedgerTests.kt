package com.example.mypasture.billing

import com.example.mypasture.auth.infrastructure.UserRepository
import com.example.mypasture.billing.application.BillingLedgerService
import com.example.mypasture.billing.domain.*
import com.example.mypasture.billing.infrastructure.BillingCreditReservationRepository
import com.example.mypasture.billing.infrastructure.BillingPurchaseRepository
import com.example.mypasture.user.domain.UserEntity
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 실제 트랜잭션으로 환불 범위, 복원, 소유권 및 마지막 이용권의 동시 예약을 검증합니다. */
@SpringBootTest
class BillingLedgerTests(
    @Autowired private val users: UserRepository,
    @Autowired private val ledger: BillingLedgerService,
    @Autowired private val purchases: BillingPurchaseRepository,
    @Autowired private val reservations: BillingCreditReservationRepository,
    @Autowired private val transaction: TransactionTemplate,
) {
    @Test
    fun `cancel renewal preserves access and refund of old purchase preserves another subscription`() {
        val user = user()
        val old = subscription(user.id)
        val current = subscription(user.id).copy(accessExpiresAt = Instant.now().plus(365, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS), product = BillingProduct.YEARLY)
        ledger.applyVerifiedPurchase(old)
        ledger.applyVerifiedPurchase(current)
        ledger.applyVerifiedPurchase(old.copy(eventKey = "cancel", revision = 2, autoRenew = false))
        assertEquals(current.accessExpiresAt!!.truncatedTo(ChronoUnit.MICROS), ledger.premiumExpiresAt(user.id, ENV))
        ledger.applyVerifiedPurchase(old.copy(eventKey = "refund", revision = 3, accessRevoked = true, financialStatus = BillingFinancialStatus.REFUNDED))
        assertEquals(current.accessExpiresAt.truncatedTo(ChronoUnit.MICROS), ledger.premiumExpiresAt(user.id, ENV))
    }

    @Test
    fun `refund without revocation does not decide access from money state`() {
        val snapshot = subscription(user().id)
        ledger.applyVerifiedPurchase(snapshot.copy(financialStatus = BillingFinancialStatus.REFUNDED))
        assertNotNull(ledger.premiumExpiresAt(snapshot.userId, ENV))
    }

    @Test
    fun `duplicate and old observations never restore revoked access`() {
        val initial = subscription(user().id)
        assertEquals(BillingApplyResult.APPLIED, ledger.applyVerifiedPurchase(initial))
        assertEquals(BillingApplyResult.DUPLICATE, ledger.applyVerifiedPurchase(initial))
        val refunded = initial.copy(eventKey = "refund", revision = 3, accessRevoked = true, financialStatus = BillingFinancialStatus.REFUNDED)
        ledger.applyVerifiedPurchase(refunded)
        assertEquals(BillingApplyResult.STALE, ledger.applyVerifiedPurchase(initial.copy(eventKey = "delayed", revision = 2)))
        assertNull(ledger.premiumExpiresAt(initial.userId, ENV))
        assertThrows(BillingConflictException::class.java) { ledger.applyVerifiedPurchase(refunded.copy(accessRevoked = false)) }
        ledger.applyVerifiedPurchase(initial.copy(eventKey = "refund-reversed", revision = 4))
        assertNotNull(ledger.premiumExpiresAt(initial.userId, ENV))
    }

    @Test
    fun `expired future and sandbox purchases do not grant production access`() {
        val owner = user().id
        val now = Instant.now()
        ledger.applyVerifiedPurchase(subscription(owner).copy(accessStartsAt = now.minusSeconds(100), accessExpiresAt = now.minusSeconds(1)))
        ledger.applyVerifiedPurchase(subscription(owner).copy(accessStartsAt = now.plusSeconds(100)))
        assertNull(ledger.premiumExpiresAt(owner, ENV))
        ledger.applyVerifiedPurchase(subscription(owner))
        assertNotNull(ledger.premiumExpiresAt(owner, ENV))
        assertNull(ledger.premiumExpiresAt(owner, BillingEnvironment.PRODUCTION))
    }

    @Test
    fun `purchase cannot move to another user or change product`() {
        val purchase = credit(user().id)
        ledger.applyVerifiedPurchase(purchase)
        assertThrows(BillingConflictException::class.java) { ledger.applyVerifiedPurchase(purchase.copy(userId = user().id)) }
        assertThrows(BillingConflictException::class.java) {
            ledger.applyVerifiedPurchase(subscription(purchase.userId).copy(transactionKey = purchase.transactionKey))
        }
    }

    @Test
    fun `parallel reservations cannot overspend three credits`() {
        val snapshot = credit(user().id)
        ledger.applyVerifiedPurchase(snapshot)
        val results = parallel(6) { ledger.reserveCredit(snapshot.userId, UUID.randomUUID(), ENV) }
        assertEquals(3, results.count { it.isSuccess })
        assertEquals(3, results.count { it.exceptionOrNull() is BillingCreditUnavailableException })
    }

    @Test
    fun `parallel duplicate request allocates a single credit`() {
        val snapshot = credit(user().id)
        ledger.applyVerifiedPurchase(snapshot)
        val session = UUID.randomUUID()
        val results = parallel(4) { ledger.reserveCredit(snapshot.userId, session, ENV) }
        assertTrue(results.all { it.isSuccess })
        assertEquals(1, results.map { it.getOrThrow().id }.distinct().size)
    }

    @Test
    fun `failure releases credit while late failure does not undo completion`() {
        val snapshot = credit(user().id).copy(entitledUnits = 1)
        ledger.applyVerifiedPurchase(snapshot)
        val first = UUID.randomUUID()
        ledger.reserveCredit(snapshot.userId, first, ENV)
        ledger.releaseCredit(snapshot.userId, first)
        assertEquals(BillingReservationStatus.RELEASED, ledger.reserveCredit(snapshot.userId, first, ENV).status)
        assertThrows(BillingReservationException::class.java) { ledger.completeCredit(snapshot.userId, first) }
        val second = UUID.randomUUID()
        ledger.reserveCredit(snapshot.userId, second, ENV)
        ledger.completeCredit(snapshot.userId, second)
        ledger.completeCredit(snapshot.userId, second)
        assertEquals(BillingReservationStatus.COMPLETED, ledger.releaseCredit(snapshot.userId, second).status)
        assertThrows(BillingCreditUnavailableException::class.java) { ledger.reserveCredit(snapshot.userId, UUID.randomUUID(), ENV) }
    }

    @Test
    fun `refund releases only its pending lot and reversal does not regrant spent credits`() {
        val owner = user().id
        val first = credit(owner)
        ledger.applyVerifiedPurchase(first)
        val completed = UUID.randomUUID()
        ledger.reserveCredit(owner, completed, ENV)
        ledger.completeCredit(owner, completed)
        val pending = UUID.randomUUID()
        ledger.reserveCredit(owner, pending, ENV)
        ledger.applyVerifiedPurchase(first.copy(eventKey = "refund", revision = 2, entitledUnits = 0, accessRevoked = true, financialStatus = BillingFinancialStatus.REFUNDED))
        assertEquals(BillingReservationStatus.RELEASED, reservations.findByUserIdAndSessionId(owner, pending)!!.status)
        ledger.applyVerifiedPurchase(first.copy(eventKey = "reversed", revision = 3))
        assertEquals(2, parallel(3) { ledger.reserveCredit(owner, UUID.randomUUID(), ENV) }.count { it.isSuccess })
        val other = credit(owner).copy(entitledUnits = 1)
        ledger.applyVerifiedPurchase(other)
        val otherSession = UUID.randomUUID()
        ledger.reserveCredit(owner, otherSession, ENV)
        ledger.applyVerifiedPurchase(first.copy(eventKey = "refund-again", revision = 4, entitledUnits = 0, accessRevoked = true))
        assertEquals(BillingReservationStatus.COMPLETED, ledger.completeCredit(owner, otherSession).status)
    }

    @Test
    fun `partial refund cannot make spent credit negative or take another lot`() {
        val snapshot = credit(user().id)
        ledger.applyVerifiedPurchase(snapshot)
        repeat(2) {
            val session = UUID.randomUUID()
            ledger.reserveCredit(snapshot.userId, session, ENV)
            ledger.completeCredit(snapshot.userId, session)
        }
        ledger.applyVerifiedPurchase(snapshot.copy(eventKey = "partial", revision = 2, entitledUnits = 1, financialStatus = BillingFinancialStatus.PARTIALLY_REFUNDED))
        assertThrows(BillingCreditUnavailableException::class.java) { ledger.reserveCredit(snapshot.userId, UUID.randomUUID(), ENV) }
    }

    @Test
    fun `answer transaction rollback also rolls back credit completion`() {
        val snapshot = credit(user().id)
        ledger.applyVerifiedPurchase(snapshot)
        val session = UUID.randomUUID()
        ledger.reserveCredit(snapshot.userId, session, ENV)
        assertThrows(IllegalStateException::class.java) {
            transaction.executeWithoutResult {
                ledger.completeCredit(snapshot.userId, session)
                error("Simulated answer persistence failure")
            }
        }
        assertEquals(BillingReservationStatus.RESERVED, reservations.findByUserIdAndSessionId(snapshot.userId, session)!!.status)
        ledger.releaseCredit(snapshot.userId, session)
    }

    @Test
    fun `same transaction identifier in different stores is independent`() {
        val snapshot = credit(user().id)
        ledger.applyVerifiedPurchase(snapshot)
        ledger.applyVerifiedPurchase(snapshot.copy(store = BillingStore.GOOGLE))
        assertNotNull(purchases.findByStoreAndEnvironmentAndTransactionKey(BillingStore.GOOGLE, ENV, snapshot.transactionKey))
    }

    @Test
    fun `withdrawn account accepts existing refund updates but cannot reserve or get access`() {
        val owner = user()
        val snapshot = subscription(owner.id)
        ledger.applyVerifiedPurchase(snapshot)
        owner.withdraw(Instant.now())
        users.saveAndFlush(owner)
        assertEquals(BillingApplyResult.APPLIED, ledger.applyVerifiedPurchase(snapshot.copy(
            eventKey = "refund-after-withdrawal", revision = 2, financialStatus = BillingFinancialStatus.REFUNDED, accessRevoked = true,
        )))
        assertNull(ledger.premiumExpiresAt(owner.id, ENV))
        assertThrows(BillingConflictException::class.java) { ledger.reserveCredit(owner.id, UUID.randomUUID(), ENV) }
        assertThrows(BillingConflictException::class.java) { ledger.applyVerifiedPurchase(credit(owner.id)) }
    }

    private fun user() = users.save(UserEntity(displayName = "Billing Test"))
    private fun subscription(userId: UUID) = VerifiedPurchaseSnapshot(
        userId, BillingStore.APPLE, ENV, UUID.randomUUID().toString(), "purchased", 1, BillingProduct.MONTHLY,
        BillingFinancialStatus.PAID, false, Instant.now().minusSeconds(10).truncatedTo(ChronoUnit.MICROS),
        Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS), true, 0,
    )
    private fun credit(userId: UUID) = subscription(userId).copy(
        product = BillingProduct.DEEP_THREE_SESSIONS, accessStartsAt = null, accessExpiresAt = null, autoRenew = false, entitledUnits = 3,
    )
    private fun <T> parallel(count: Int, work: () -> T): List<Result<T>> {
        val executor = Executors.newFixedThreadPool(count)
        val ready = CountDownLatch(count)
        val start = CountDownLatch(1)
        try {
            val futures = (1..count).map {
                executor.submit<Result<T>> { ready.countDown(); check(start.await(15, TimeUnit.SECONDS)); runCatching(work) }
            }
            check(ready.await(15, TimeUnit.SECONDS))
            start.countDown()
            return futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }
    companion object { private val ENV = BillingEnvironment.SANDBOX }
}
