package com.example.mypasture.ai.infrastructure

import com.example.mypasture.ai.application.AiUsagePort
import com.example.mypasture.ai.domain.AiUsageLogEntity
import com.example.mypasture.ai.domain.AiUsageStatus
import com.example.mypasture.ai.domain.AiRuntimeConfigSnapshot
import com.example.mypasture.ai.domain.AiCompletedTurn
import com.example.mypasture.ai.domain.AiClientRequest
import com.example.mypasture.ai.domain.AiClientRequestStatus
import com.example.mypasture.ai.domain.AiIdempotencyConflictException
import com.example.mypasture.ai.domain.AiRequestReceiptEntity
import com.example.mypasture.ai.domain.AiRequestReceiptStatus
import com.example.mypasture.ai.domain.AiUsageReservation
import com.example.mypasture.ai.domain.GroundedAnswer
import com.example.mypasture.auth.infrastructure.UserRepository
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Component
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Transactional
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import jakarta.persistence.LockModeType
import java.security.MessageDigest
import org.slf4j.LoggerFactory

/** AI 사용량 로그를 PostgreSQL과 개발용 H2에서 동일하게 저장하는 repository입니다. */
interface AiUsageLogRepository : JpaRepository<AiUsageLogEntity, UUID> {
    fun countByUserIdAndCreatedAtGreaterThanEqual(userId: UUID, createdAt: Instant): Long

    fun countByUserIdAndQuotaCountedTrueAndStatusInAndCreatedAtGreaterThanEqual(
        userId: UUID,
        statuses: Collection<AiUsageStatus>,
        createdAt: Instant,
    ): Long

    fun findByIdAndUserIdAndStatus(id: UUID, userId: UUID, status: AiUsageStatus): AiUsageLogEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findLockedByIdAndUserIdAndStatus(id: UUID, userId: UUID, status: AiUsageStatus): AiUsageLogEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select usage from AiUsageLogEntity usage where usage.id = :id")
    fun findLockedById(id: UUID): AiUsageLogEntity?

    fun findAllByUserId(userId: UUID): List<AiUsageLogEntity>
    fun deleteAllByCreatedAtBefore(cutoff: Instant): Long
}

/** JPA로 AI 사용량을 저장하고 사용자의 UTC 기준 일일 요청 횟수를 조회합니다. */
@Repository
class JdbcAiUsageAdapter(
    private val repository: AiUsageLogRepository,
    private val receiptRepository: AiRequestReceiptRepository,
    private val responseCodec: AiRequestResponseCodec,
    private val userRepository: UserRepository,
    private val properties: com.example.mypasture.config.AiProperties,
) : AiUsagePort {
    override fun countToday(userId: UUID): Long =
        repository.countByUserIdAndQuotaCountedTrueAndStatusInAndCreatedAtGreaterThanEqual(
            userId,
            QUOTA_STATUSES,
            LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC),
        )

    /** 사용자 행을 잠근 상태에서 사용량을 다시 세어 동시 요청의 quota 우회를 차단합니다. */
    @Transactional
    override fun reserve(
        userId: UUID,
        question: String,
        dailyLimit: Long,
        config: AiRuntimeConfigSnapshot?,
        clientRequestId: UUID?,
        requestFingerprint: String,
    ): AiUsageReservation? {
        userRepository.findActiveByIdForUpdate(userId) ?: return null
        val fingerprintHash = clientRequestId?.let { hash(requestFingerprint) }
        if (clientRequestId != null) {
            receiptRepository.findLockedByUserIdAndClientRequestId(userId, clientRequestId)?.let { existing ->
                if (!MessageDigest.isEqual(existing.requestHash.toByteArray(), fingerprintHash!!.toByteArray())) {
                    throw AiIdempotencyConflictException()
                }
                return AiUsageReservation.Existing(existing.toClientRequest())
            }
        }
        if (countToday(userId) >= dailyLimit) return null
        val usage = repository.save(
            AiUsageLogEntity(
                userId = userId,
                requestHash = hash(question),
                model = config?.generatorModel ?: properties.generatorModel,
                modelConfigVersionId = config?.id,
                evaluatorModel = config?.evaluatorModel?.takeIf { config.evaluationEnabled },
            ),
        )
        if (clientRequestId != null) {
            receiptRepository.save(
                AiRequestReceiptEntity(
                    userId = userId,
                    clientRequestId = clientRequestId,
                    usageLogId = usage.id,
                    requestHash = fingerprintHash!!,
                ),
            )
        }
        return AiUsageReservation.Started(usage.id)
    }

    @Transactional(readOnly = true)
    override fun findClientRequest(userId: UUID, clientRequestId: UUID): AiClientRequest? =
        receiptRepository.findByUserIdAndClientRequestId(userId, clientRequestId)?.toClientRequest()

    @Transactional
    override fun reserveRegeneration(
        userId: UUID,
        question: String,
        parentTurnId: UUID,
        config: AiRuntimeConfigSnapshot,
    ): UUID {
        userRepository.findActiveByIdForUpdate(userId) ?: error("AI 사용자를 찾을 수 없습니다.")
        repository.findLockedByIdAndUserIdAndStatus(parentTurnId, userId, AiUsageStatus.COMPLETED)
            ?: error("피드백 대상 AI 답변을 찾을 수 없습니다.")
        return repository.save(
            AiUsageLogEntity(
                userId = userId,
                requestHash = hash(question),
                model = config.generatorModel,
                modelConfigVersionId = config.id,
                evaluatorModel = config.evaluatorModel.takeIf { config.evaluationEnabled },
                quotaCounted = false,
                parentTurnId = parentTurnId,
            ),
        ).id
    }

    @Transactional(readOnly = true)
    override fun findCompletedTurn(userId: UUID, turnId: UUID): AiCompletedTurn? =
        repository.findByIdAndUserIdAndStatus(turnId, userId, AiUsageStatus.COMPLETED)?.let { entity ->
            AiCompletedTurn(
                turnId = entity.id,
                citationVerseIds = entity.citationVerseIds
                    ?.split(',')
                    ?.mapNotNull(String::toLongOrNull)
                    ?.toSet()
                    .orEmpty(),
            )
        }

    @Transactional(readOnly = true)
    override fun questionMatches(userId: UUID, turnId: UUID, question: String): Boolean {
        val entity = repository.findByIdAndUserIdAndStatus(turnId, userId, AiUsageStatus.COMPLETED) ?: return false
        return MessageDigest.isEqual(entity.requestHash.toByteArray(), hash(question).toByteArray())
    }

    @Transactional
    override fun complete(reservationId: UUID, answer: GroundedAnswer) {
        repository.findLockedById(reservationId)?.complete(answer, properties.embeddingModel)
            ?: error("AI usage reservation not found: $reservationId")
        val now = Instant.now()
        receiptRepository.findLockedByUsageLogId(reservationId)?.complete(
            encryptedResponse = responseCodec.encode(answer),
            responseExpiresAt = now.plusSeconds(properties.idempotencyResponseTtlSeconds),
            now = now,
        )
    }

    @Transactional
    override fun fail(reservationId: UUID, errorCode: String, consumedAnswer: GroundedAnswer?) {
        repository.findLockedById(reservationId)?.let {
            if (consumedAnswer != null) it.complete(consumedAnswer, properties.embeddingModel)
            it.fail(errorCode)
        }
        receiptRepository.findLockedByUsageLogId(reservationId)?.fail(errorCode, Instant.now())
    }

    private fun AiRequestReceiptEntity.toClientRequest(now: Instant = Instant.now()): AiClientRequest {
        if (status == AiRequestReceiptStatus.PENDING) {
            return AiClientRequest(clientRequestId, usageLogId, AiClientRequestStatus.PROCESSING, updatedAt = updatedAt)
        }
        if (status == AiRequestReceiptStatus.FAILED) {
            return AiClientRequest(clientRequestId, usageLogId, AiClientRequestStatus.FAILED, errorCode = errorCode, updatedAt = updatedAt)
        }
        val payload = encryptedResponse
        val expiresAt = responseExpiresAt
        if (payload == null || expiresAt == null || !expiresAt.isAfter(now)) {
            return AiClientRequest(clientRequestId, usageLogId, AiClientRequestStatus.EXPIRED, updatedAt = updatedAt)
        }
        val answer = runCatching { responseCodec.decode(payload) }
            .onFailure { logger.error("AI request receipt response cannot be decrypted receiptId={}", id, it) }
            .getOrNull()
            ?: return AiClientRequest(clientRequestId, usageLogId, AiClientRequestStatus.EXPIRED, updatedAt = updatedAt)
        return AiClientRequest(clientRequestId, usageLogId, AiClientRequestStatus.COMPLETED, answer = answer, updatedAt = updatedAt)
    }

    private fun hash(value: String): String =
        Mac.getInstance("HmacSHA256")
            .apply { init(SecretKeySpec(properties.privacyHashSecret.toByteArray(), "HmacSHA256")) }
            .doFinal(value.lowercase().trim().toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        private val logger = LoggerFactory.getLogger(JdbcAiUsageAdapter::class.java)
        // PENDING은 동시 요청 우회를 막고 COMPLETED만 실제 사용량으로 남깁니다. 실패 요청은 재시도할 수 있습니다.
        private val QUOTA_STATUSES = listOf(AiUsageStatus.PENDING, AiUsageStatus.COMPLETED)
    }
}

/** 개인정보 처리방침의 1년 보유기간에 맞춰 만료된 AI 사용량·비용 기록을 매일 삭제합니다. */
@Component
class AiUsageLogCleanupJob(private val repository: AiUsageLogRepository) {
    @Scheduled(cron = "0 40 3 * * *")
    @Transactional
    fun deleteExpiredUsageLogs() {
        repository.deleteAllByCreatedAtBefore(Instant.now().minusSeconds(365L * 24 * 60 * 60))
    }
}
