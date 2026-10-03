package com.example.mypasture.billing.application

import com.example.mypasture.auth.infrastructure.UserRepository
import com.example.mypasture.billing.domain.*
import com.example.mypasture.billing.infrastructure.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/** 짧은 DB 트랜잭션으로 작업을 예약하고 외부 조회 결과를 fencing 검증 후 원장에 반영합니다. */
@Service
class BillingVerificationService(
    private val properties: BillingProperties,
    private val users: UserRepository,
    private val accounts: BillingAccountRepository,
    private val jobs: BillingVerificationJobRepository,
    private val cipher: BillingProofCipher,
    private val ledger: BillingLedgerService,
    private val purchases: BillingPurchaseRepository,
    private val sources: BillingSourceRepository,
) {
    @Transactional
    fun accountToken(userId: UUID): UUID {
        enabled()
        users.findActiveByIdForUpdate(userId) ?: throw BillingRequestNotFound()
        return account(userId).accountToken
    }

    @Transactional
    fun enqueue(userId: UUID, requestId: UUID, store: BillingStore, productId: String, proof: String, notification: Boolean = false): BillingVerificationView {
        enabled()
        val owner = users.findIncludingWithdrawnByIdForUpdate(userId) ?: throw BillingRequestNotFound()
        if (owner.deletedAt != null && !notification) throw BillingRequestNotFound()
        val products = when (store) {
            BillingStore.APPLE -> properties.apple.products.takeIf { properties.apple.enabled }
            BillingStore.GOOGLE -> properties.google.products.takeIf { properties.google.enabled }
        } ?: throw BillingNotEnabled()
        if (productId !in products || productId.length > 200) throw StorePurchaseRejected("PRODUCT_NOT_ALLOWED")
        if (proof.isBlank() || proof.length > 8192) throw StorePurchaseRejected("INVALID_PURCHASE_PROOF")
        account(userId)
        val hash = cipher.fingerprint("${properties.environment}:$store:$productId:$proof")
        jobs.findByUserIdAndRequestId(userId, requestId)?.let {
            if (it.requestHash != hash) throw BillingConflictException()
            return it.view()
        }
        if (!notification && jobs.countByUserIdAndCreatedAtAfter(userId, Instant.now().minusSeconds(86400)) >= 30) {
            throw StorePurchaseRejected("PURCHASE_REQUEST_LIMIT")
        }
        val id = UUID.randomUUID()
        return jobs.save(BillingVerificationJobEntity(id, userId, requestId, store, properties.environment, productId, hash, cipher.encrypt(proof, id))).view()
    }

    @Transactional(readOnly = true)
    fun status(userId: UUID, id: UUID): BillingVerificationView = jobs.findByIdAndUserId(id, userId)?.view() ?: throw BillingRequestNotFound()

    @Transactional(readOnly = true)
    fun entitlements(userId: UUID): BillingEntitlements {
        if (!properties.enabled) return BillingEntitlements(false, null, 0)
        if (users.findByIdAndDeletedAtIsNull(userId) == null) throw BillingRequestNotFound()
        return BillingEntitlements(true, ledger.premiumExpiresAt(userId, properties.environment), purchases.availableCredits(userId, properties.environment.name))
    }

    fun notificationOwner(token: UUID): UUID = accounts.findByAccountToken(token)?.userId ?: throw StorePurchaseRejected("PURCHASE_ACCOUNT_NOT_LINKED")

    fun dueJobs(): List<UUID> = if (!properties.enabled) emptyList() else jobs.due(Instant.now(),
        listOf(BillingVerificationPhase.VERIFY, BillingVerificationPhase.ACKNOWLEDGE), BillingVerificationPhase.COMPLETE,
        org.springframework.data.domain.PageRequest.of(0, 20)).map { it.id }

    @Transactional
    fun claim(id: UUID): BillingVerificationWork? {
        enabled()
        val candidate = jobs.findById(id).orElse(null) ?: return null
        users.findIncludingWithdrawnByIdForUpdate(candidate.userId) ?: return null
        // 다른 트랜잭션이 사용자 잠금을 보유하는 동안 읽은 엔티티는 반드시 새로 고칩니다.
        entityManager.refresh(candidate)
        val account = account(candidate.userId)
        val now = Instant.now()
        if (account.leaseExpiresAt?.isAfter(now) == true || candidate.nextAttemptAt.isAfter(now) || candidate.phase in TERMINAL) return null
        if (candidate.environment != properties.environment) return null
        if (candidate.phase == BillingVerificationPhase.COMPLETE && !candidate.refreshEnabled) return null
        if (candidate.phase == BillingVerificationPhase.COMPLETE) candidate.phase = BillingVerificationPhase.VERIFY
        val token = UUID.randomUUID()
        account.leaseToken = token
        account.leaseExpiresAt = now.plusSeconds(properties.leaseSeconds)
        account.revision++
        candidate.attempts++
        candidate.nextAttemptAt = account.leaseExpiresAt!!
        candidate.updatedAt = now
        return BillingVerificationWork(candidate.id, candidate.userId, account.accountToken, token, account.revision,
            candidate.store, candidate.productId, candidate.encryptedProof, candidate.phase)
    }

    @jakarta.persistence.PersistenceContext
    private lateinit var entityManager: jakarta.persistence.EntityManager

    @Transactional
    fun verified(work: BillingVerificationWork, verified: VerifiedStorePurchase) {
        val (account, job) = lockedWork(work) ?: return
        if (verified.ownerToken != account.accountToken) throw StorePurchaseRejected("PURCHASE_OWNER_MISMATCH")
        fun sourceId(key: String) = billingSourceKey("${job.store}:${job.environment}:$key")
        val currentSource = sources.findById(sourceId(verified.sourceKey)).orElse(null)
        if (currentSource != null && currentSource.userId != job.userId) throw BillingConflictException()
        if (currentSource?.supersededBy != null) throw StorePurchaseRejected("PURCHASE_SOURCE_REPLACED")
        if (currentSource == null) sources.save(BillingSourceEntity(sourceId(verified.sourceKey), job.userId))
        verified.replacesSourceKey?.takeIf { it != verified.sourceKey }?.let { key ->
            val old = sources.findById(sourceId(key)).orElseGet { BillingSourceEntity(sourceId(key), job.userId) }
            if (old.userId != job.userId || (old.supersededBy != null && old.supersededBy != verified.sourceKey)) throw BillingConflictException()
            old.supersededBy = verified.sourceKey
            sources.save(old)
        }
        val sourceKeys = listOfNotNull(verified.sourceKey, verified.replacesSourceKey).distinct()
        sourceKeys.forEach { key ->
            purchases.findAllByStoreAndEnvironmentAndSourceKey(job.store, job.environment, key).forEach { previous ->
                if (previous.userId != job.userId) throw BillingConflictException()
                if (previous.transactionKey != verified.transactionKey && previous.product.units == 0) {
                    ledger.applyVerifiedPurchase(VerifiedPurchaseSnapshot(job.userId, job.store, job.environment,
                        previous.transactionKey, "${job.id}:${work.revision}:superseded", work.revision, previous.product,
                        previous.financialStatus, true, previous.accessStartsAt, previous.accessExpiresAt, false, 0, previous.sourceKey))
                }
            }
        }
        ledger.applyVerifiedPurchase(VerifiedPurchaseSnapshot(job.userId, job.store, job.environment, verified.transactionKey,
            "${job.id}:${work.revision}", work.revision, verified.product, verified.financialStatus, verified.revoked,
            verified.startsAt, verified.expiresAt, verified.autoRenew, verified.units, verified.sourceKey))
        job.recurring = verified.product.units == 0
        job.lastError = null
        job.attempts = 0
        job.phase = if (verified.needsAcknowledgement) BillingVerificationPhase.ACKNOWLEDGE else BillingVerificationPhase.COMPLETE
        if (job.phase == BillingVerificationPhase.COMPLETE) enableRefresh(job)
        job.nextAttemptAt = if (job.phase == BillingVerificationPhase.ACKNOWLEDGE) Instant.now() else nextRefresh(job)
        release(account, job)
    }

    @Transactional
    fun acknowledged(work: BillingVerificationWork) {
        val (account, job) = lockedWork(work) ?: return
        job.phase = BillingVerificationPhase.COMPLETE
        enableRefresh(job)
        job.attempts = 0
        job.lastError = null
        job.nextAttemptAt = nextRefresh(job)
        release(account, job)
    }

    @Transactional
    fun failed(work: BillingVerificationWork, reason: String, permanent: Boolean) {
        val (account, job) = lockedWork(work) ?: return
        job.lastError = reason.take(80)
        job.phase = when {
            permanent && job.phase == BillingVerificationPhase.VERIFY -> BillingVerificationPhase.REJECTED
            job.attempts >= properties.maxAttempts -> BillingVerificationPhase.DEAD_LETTER
            else -> job.phase
        }
        job.nextAttemptAt = Instant.now().plusSeconds((5L shl job.attempts.coerceAtMost(10)).coerceAtMost(3600))
        release(account, job)
    }

    private fun lockedWork(work: BillingVerificationWork): Pair<BillingAccountEntity, BillingVerificationJobEntity>? {
        users.findIncludingWithdrawnByIdForUpdate(work.userId) ?: return null
        val account = account(work.userId)
        if (account.leaseToken != work.leaseToken || account.revision != work.revision || account.leaseExpiresAt?.isAfter(Instant.now()) != true) return null
        val job = jobs.findById(work.id).orElseThrow { BillingRequestNotFound() }
        return account to job
    }
    private fun account(userId: UUID) = accounts.findById(userId).orElseGet { accounts.save(BillingAccountEntity(userId)) }
    private fun enableRefresh(job: BillingVerificationJobEntity) {
        // 동일 증거의 알림이 반복되어도 정기 조회는 한 작업만 담당합니다.
        jobs.findAllByUserIdAndRequestHashAndRefreshEnabledTrue(job.userId, job.requestHash).forEach { it.refreshEnabled = false }
        job.refreshEnabled = true
    }
    private fun nextRefresh(job: BillingVerificationJobEntity) = Instant.now().plusSeconds(if (job.recurring) 21600 else 86400)
    private fun release(account: BillingAccountEntity, job: BillingVerificationJobEntity) {
        account.leaseToken = null
        account.leaseExpiresAt = null
        job.updatedAt = Instant.now()
    }
    private fun enabled() { if (!properties.enabled) throw BillingNotEnabled() }
    private fun BillingVerificationJobEntity.view() = BillingVerificationView(id, phase, lastError, updatedAt)
    companion object { private val TERMINAL = setOf(BillingVerificationPhase.REJECTED, BillingVerificationPhase.DEAD_LETTER) }
}

/** 원문 구매 증거는 외부 응답에 포함하지 않습니다. */
data class BillingVerificationView(val id: UUID, val phase: BillingVerificationPhase, val errorCode: String?, val updatedAt: Instant)
data class BillingEntitlements(val enabled: Boolean, val premiumExpiresAt: Instant?, val availableDeepSessions: Long)

/** 외부 API 호출 동안 DB 엔티티나 트랜잭션을 붙잡지 않는 일회성 작업 값입니다. */
class BillingVerificationWork(val id: UUID, val userId: UUID, val ownerToken: UUID, val leaseToken: UUID, val revision: Long,
    val store: BillingStore, val productId: String, val encryptedProof: String, val phase: BillingVerificationPhase)
