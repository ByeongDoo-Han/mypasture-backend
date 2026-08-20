package com.example.mypasture.ai.infrastructure

import com.example.mypasture.ai.application.AiUsagePort
import com.example.mypasture.ai.domain.AiUsageLogEntity
import com.example.mypasture.ai.domain.GroundedAnswer
import com.example.mypasture.auth.infrastructure.UserRepository
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** AI 사용량 로그를 PostgreSQL과 개발용 H2에서 동일하게 저장하는 repository입니다. */
interface AiUsageLogRepository : JpaRepository<AiUsageLogEntity, UUID> {
    fun countByUserIdAndCreatedAtGreaterThanEqual(userId: UUID, createdAt: Instant): Long

    fun findAllByUserId(userId: UUID): List<AiUsageLogEntity>
}

/** JPA로 AI 사용량을 저장하고 사용자의 UTC 기준 일일 요청 횟수를 조회합니다. */
@Repository
class JdbcAiUsageAdapter(
    private val repository: AiUsageLogRepository,
    private val userRepository: UserRepository,
    private val properties: com.example.mypasture.config.AiProperties,
) : AiUsagePort {
    override fun countToday(userId: UUID): Long =
        repository.countByUserIdAndCreatedAtGreaterThanEqual(
            userId,
            LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC),
        )

    /** 사용자 행을 잠근 상태에서 사용량을 다시 세어 동시 요청의 quota 우회를 차단합니다. */
    @Transactional
    override fun reserve(userId: UUID, question: String, dailyLimit: Long): UUID? {
        userRepository.findActiveByIdForUpdate(userId) ?: return null
        if (countToday(userId) >= dailyLimit) return null
        return repository.save(
            AiUsageLogEntity(
                userId = userId,
                requestHash = hash(question),
                model = properties.model,
            ),
        ).id
    }

    @Transactional
    override fun complete(reservationId: UUID, answer: GroundedAnswer) {
        repository.findById(reservationId).orElseThrow().complete(answer, properties.embeddingModel)
    }

    @Transactional
    override fun fail(reservationId: UUID, errorCode: String) {
        repository.findById(reservationId).ifPresent { it.fail(errorCode) }
    }

    private fun hash(value: String): String =
        Mac.getInstance("HmacSHA256")
            .apply { init(SecretKeySpec(properties.privacyHashSecret.toByteArray(), "HmacSHA256")) }
            .doFinal(value.lowercase().trim().toByteArray())
            .joinToString("") { "%02x".format(it) }
}
