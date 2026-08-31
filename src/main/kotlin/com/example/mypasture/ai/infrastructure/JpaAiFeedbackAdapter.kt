package com.example.mypasture.ai.infrastructure

import com.example.mypasture.ai.application.AiFeedbackPort
import com.example.mypasture.ai.domain.AiAnswerFeedback
import com.example.mypasture.ai.domain.AiAnswerFeedbackEntity
import com.example.mypasture.ai.domain.AiFeedbackRating
import com.example.mypasture.ai.domain.AiFeedbackReason
import com.example.mypasture.ai.domain.AiFeedbackRegenerationConflictException
import com.example.mypasture.ai.domain.AiFeedbackRegenerationStatus
import com.example.mypasture.ai.domain.AiFeedbackRegenerationUnavailableException
import com.example.mypasture.ai.domain.AiFeedbackSummary
import com.example.mypasture.ai.domain.AiFeedbackTargetNotFoundException
import com.example.mypasture.ai.domain.AiUsageStatus
import com.example.mypasture.config.AiProperties
import jakarta.persistence.LockModeType
import org.springframework.data.domain.PageRequest
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 피드백 사유별 집계 결과를 projection으로 읽습니다. */
interface AiFeedbackReasonCount {
    fun getReasonCode(): String
    fun getTotal(): Long
}

/** 답변 피드백의 멱등 갱신, 동시 재생성 잠금, 운영 집계를 담당하는 repository입니다. */
@Repository
interface AiAnswerFeedbackRepository : JpaRepository<AiAnswerFeedbackEntity, UUID> {
    fun findByUserIdAndTurnId(userId: UUID, turnId: UUID): AiAnswerFeedbackEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findLockedByUserIdAndTurnId(userId: UUID, turnId: UUID): AiAnswerFeedbackEntity?

    fun countByRating(rating: AiFeedbackRating): Long
    fun countByRegenerationStatus(status: AiFeedbackRegenerationStatus): Long

    @Query(
        value = "select reason_code as reasonCode, count(*) as total from ai_answer_feedback_reasons group by reason_code",
        nativeQuery = true,
    )
    fun countGroupedByReason(): List<AiFeedbackReasonCount>

    @Query(
        "select distinct feedback from AiAnswerFeedbackEntity feedback join feedback.reasons reason " +
            "where reason = :reason order by feedback.updatedAt desc",
    )
    fun findPriority(reason: AiFeedbackReason, pageable: org.springframework.data.domain.Pageable): List<AiAnswerFeedbackEntity>
}

/** 선택 의견을 별도 AES-GCM 키로 암호화해 DB 유출 시 상담 내용 노출을 줄입니다. */
@Component
class AiFeedbackCipher(properties: AiProperties) {
    private val key = SecretKeySpec(
        MessageDigest.getInstance("SHA-256").digest((properties.privacyHashSecret + ":feedback:v1").toByteArray()),
        "AES",
    )
    private val random = SecureRandom()

    fun encrypt(value: String): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.size + encrypted.size).put(iv).put(encrypted).array())
    }
}

/** 사용자의 최종 평가 한 건을 유지하고 개선 답변 상태 전이를 원자적으로 처리합니다. */
@Component
class JpaAiFeedbackAdapter(
    private val feedbackRepository: AiAnswerFeedbackRepository,
    private val usageRepository: AiUsageLogRepository,
    private val cipher: AiFeedbackCipher,
) : AiFeedbackPort {
    @Transactional
    override fun upsert(
        userId: UUID,
        turnId: UUID,
        rating: AiFeedbackRating,
        reasons: Set<AiFeedbackReason>,
        comment: String?,
    ): AiAnswerFeedback {
        usageRepository.findLockedByIdAndUserIdAndStatus(turnId, userId, AiUsageStatus.COMPLETED)
            ?: throw AiFeedbackTargetNotFoundException()
        val now = Instant.now()
        val encrypted = comment?.takeIf(String::isNotBlank)?.let(cipher::encrypt)
        val entity = feedbackRepository.findLockedByUserIdAndTurnId(userId, turnId)
            ?: AiAnswerFeedbackEntity(userId = userId, turnId = turnId, rating = rating)
        entity.update(rating, reasons, encrypted, now)
        return feedbackRepository.save(entity).toModel()
    }

    @Transactional
    override fun beginRegeneration(userId: UUID, turnId: UUID): AiAnswerFeedback {
        val entity = feedbackRepository.findLockedByUserIdAndTurnId(userId, turnId)
            ?: throw AiFeedbackRegenerationUnavailableException()
        if (entity.rating != AiFeedbackRating.NOT_HELPFUL) throw AiFeedbackRegenerationUnavailableException()
        when (entity.regenerationStatus) {
            AiFeedbackRegenerationStatus.COMPLETED -> throw AiFeedbackRegenerationUnavailableException()
            AiFeedbackRegenerationStatus.PENDING -> {
                if (entity.updatedAt.isAfter(Instant.now().minus(PENDING_TIMEOUT))) {
                    throw AiFeedbackRegenerationConflictException()
                }
            }
            AiFeedbackRegenerationStatus.NONE, AiFeedbackRegenerationStatus.FAILED -> Unit
        }
        entity.beginRegeneration(Instant.now())
        return feedbackRepository.save(entity).toModel()
    }

    @Transactional
    override fun completeRegeneration(userId: UUID, turnId: UUID, regeneratedTurnId: UUID) {
        feedbackRepository.findLockedByUserIdAndTurnId(userId, turnId)
            ?.apply { completeRegeneration(regeneratedTurnId, Instant.now()) }
            ?: throw AiFeedbackRegenerationUnavailableException()
    }

    @Transactional
    override fun failRegeneration(userId: UUID, turnId: UUID) {
        feedbackRepository.findLockedByUserIdAndTurnId(userId, turnId)?.failRegeneration(Instant.now())
    }

    @Transactional(readOnly = true)
    override fun summary(): AiFeedbackSummary = AiFeedbackSummary(
        total = feedbackRepository.count(),
        helpful = feedbackRepository.countByRating(AiFeedbackRating.HELPFUL),
        notHelpful = feedbackRepository.countByRating(AiFeedbackRating.NOT_HELPFUL),
        regenerated = feedbackRepository.countByRegenerationStatus(AiFeedbackRegenerationStatus.COMPLETED),
        byReason = feedbackRepository.countGroupedByReason().mapNotNull { row ->
            runCatching { AiFeedbackReason.valueOf(row.getReasonCode()) to row.getTotal() }.getOrNull()
        }.toMap(),
    )

    @Transactional(readOnly = true)
    override fun priority(limit: Int): List<AiAnswerFeedback> =
        feedbackRepository.findPriority(
            AiFeedbackReason.WRONG_OR_NONEXISTENT_SCRIPTURE,
            PageRequest.of(0, limit.coerceIn(1, 100)),
        ).map { it.toModel() }

    private fun AiAnswerFeedbackEntity.toModel() = AiAnswerFeedback(
        turnId = turnId,
        rating = rating,
        reasons = reasons.toSet(),
        regenerationStatus = regenerationStatus,
        regeneratedTurnId = regeneratedTurnId,
        updatedAt = updatedAt,
    )

    companion object {
        private val PENDING_TIMEOUT = Duration.ofMinutes(5)
    }
}
