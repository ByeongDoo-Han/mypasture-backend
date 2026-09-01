package com.example.mypasture.ai.infrastructure

import com.example.mypasture.ai.domain.AiRequestReceiptEntity
import com.example.mypasture.ai.domain.AiRequestReceiptStatus
import com.example.mypasture.ai.domain.GroundedAnswer
import com.example.mypasture.config.AiProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 사용자 소유권으로 멱등 요청을 찾고 완료·실패 상태를 잠금 갱신합니다. */
@Repository
interface AiRequestReceiptRepository : JpaRepository<AiRequestReceiptEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findLockedByUserIdAndClientRequestId(userId: UUID, clientRequestId: UUID): AiRequestReceiptEntity?

    fun findByUserIdAndClientRequestId(userId: UUID, clientRequestId: UUID): AiRequestReceiptEntity?

    fun findByUsageLogId(usageLogId: UUID): AiRequestReceiptEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findLockedByUsageLogId(usageLogId: UUID): AiRequestReceiptEntity?

    @org.springframework.data.jpa.repository.Modifying
    @Query(
        "update AiRequestReceiptEntity receipt set receipt.encryptedResponse = null " +
            "where receipt.status = :status and receipt.responseExpiresAt <= :now and receipt.encryptedResponse is not null",
    )
    fun clearExpiredResponses(status: AiRequestReceiptStatus, now: Instant): Int

    fun findTop500ByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
        status: AiRequestReceiptStatus,
        cutoff: Instant,
    ): List<AiRequestReceiptEntity>

    fun deleteAllByStatusNotAndCreatedAtBefore(status: AiRequestReceiptStatus, cutoff: Instant): Long
}

/** 재전달에 필요한 최종 AI 응답을 용도별 AES-GCM 키로 암호화합니다. */
@Component
class AiRequestResponseCodec(
    properties: AiProperties,
    private val objectMapper: ObjectMapper,
) {
    private val key = SecretKeySpec(
        MessageDigest.getInstance("SHA-256")
            .digest((properties.privacyHashSecret + ":request-receipt:v1").toByteArray()),
        "AES",
    )
    private val random = SecureRandom()

    fun encode(answer: GroundedAnswer): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(objectMapper.writeValueAsBytes(answer))
        return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.size + encrypted.size).put(iv).put(encrypted).array())
    }

    fun decode(payload: String): GroundedAnswer {
        val decoded = Base64.getDecoder().decode(payload)
        require(decoded.size > 12) { "Encrypted AI request response is invalid." }
        val iv = decoded.copyOfRange(0, 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return objectMapper.readValue(cipher.doFinal(decoded.copyOfRange(12, decoded.size)))
    }
}

/** 중단된 요청과 만료된 암호문을 한 트랜잭션으로 정리하는 worker application service입니다. */
@Component
class AiRequestReceiptMaintenanceService(
    private val repository: AiRequestReceiptRepository,
    private val usageRepository: AiUsageLogRepository,
    private val properties: AiProperties,
) {
    @Transactional
    fun failStalePendingRequests() {
        val now = Instant.now()
        val cutoff = now.minusMillis(properties.idempotencyPendingTimeoutMs)
        repository.findTop500ByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(AiRequestReceiptStatus.PENDING, cutoff)
            .forEach { candidate ->
                // 정상 완료 경로와 같은 잠금 순서를 사용해 usage와 receipt 상태를 함께 확정합니다.
                val usage = usageRepository.findLockedById(candidate.usageLogId) ?: return@forEach
                val receipt = repository.findLockedByUsageLogId(candidate.usageLogId) ?: return@forEach
                if (receipt.status == AiRequestReceiptStatus.PENDING && receipt.updatedAt.isBefore(cutoff)) {
                    if (usage.status == com.example.mypasture.ai.domain.AiUsageStatus.PENDING) {
                        usage.fail("AI_REQUEST_STALE")
                    }
                    receipt.fail("AI_REQUEST_STALE", now)
                }
            }
    }

    @Transactional
    fun removeExpiredRequestData() {
        val now = Instant.now()
        repository.clearExpiredResponses(AiRequestReceiptStatus.COMPLETED, now)
        repository.deleteAllByStatusNotAndCreatedAtBefore(
            AiRequestReceiptStatus.PENDING,
            now.minus(properties.idempotencyReceiptRetentionDays, ChronoUnit.DAYS),
        )
    }
}

/** API 복제본이 아닌 단일 worker에서만 AI 요청 영수증 정리를 예약 실행합니다. */
@Component
@ConditionalOnProperty(prefix = "app.worker", name = ["enabled"], havingValue = "true")
class AiRequestReceiptMaintenanceJob(
    private val maintenanceService: AiRequestReceiptMaintenanceService,
) {
    @Scheduled(fixedDelayString = "\${app.ai.idempotency-recovery-delay-ms:60000}")
    fun failStalePendingRequests() = maintenanceService.failStalePendingRequests()

    @Scheduled(cron = "0 10 3 * * *")
    fun removeExpiredRequestData() = maintenanceService.removeExpiredRequestData()
}
