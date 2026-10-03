package com.example.mypasture.billing.application

import com.example.mypasture.billing.domain.BillingConflictException
import com.example.mypasture.billing.domain.BillingVerificationPhase
import org.springframework.stereotype.Service
import org.springframework.stereotype.Component
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.slf4j.LoggerFactory

/** DB 커밋과 스토어 승인 사이의 장애도 재시도로 복구하며 토큰 원문을 로그에 남기지 않습니다. */
@Service
class BillingVerificationWorker(private val service: BillingVerificationService, ports: List<StorePurchasePort>,
    private val cipher: com.example.mypasture.billing.infrastructure.BillingProofCipher) {
    private val stores = ports.associateBy { it.store }
    fun process(id: java.util.UUID) {
        val work = service.claim(id) ?: return
        try {
            val proof = cipher.decrypt(work.encryptedProof, work.id)
            val store = stores[work.store] ?: throw BillingNotEnabled()
            if (work.phase == BillingVerificationPhase.ACKNOWLEDGE) {
                store.acknowledge(work.productId, proof)
                service.acknowledged(work)
            } else {
                val result = store.verify(work.productId, proof)
                if (result.ownerToken != work.ownerToken) throw StorePurchaseRejected("PURCHASE_OWNER_MISMATCH")
                service.verified(work, result)
            }
        } catch (error: StorePurchaseRejected) { service.failed(work, error.reason, true) }
        catch (_: BillingConflictException) { service.failed(work, "PURCHASE_LEDGER_CONFLICT", true) }
        catch (_: Exception) {
            logger.warn("Billing verification will retry jobId={}", work.id)
            service.failed(work, "STORE_TEMPORARILY_UNAVAILABLE", false)
        }
    }
    fun drain() = service.dueJobs().forEach { id ->
        try { process(id) } catch (_: Exception) { logger.error("Billing job needs recovery jobId={}", id) }
    }
    companion object { private val logger = LoggerFactory.getLogger(BillingVerificationWorker::class.java) }
}

/** Cloud Run worker에서만 검증 및 주기적 상태 재조회를 실행합니다. 여러 worker도 DB lease로 조정합니다. */
@Component
@ConditionalOnProperty(prefix = "app.worker", name = ["enabled"], havingValue = "true")
class BillingVerificationSchedule(private val worker: BillingVerificationWorker) {
    @Scheduled(fixedDelayString = "\${app.billing.poll-delay-ms:5000}") fun run() = worker.drain()
}
