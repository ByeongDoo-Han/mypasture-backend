package com.example.mypasture.ai.infrastructure

import com.example.mypasture.ai.application.AiUsagePort
import com.example.mypasture.ai.domain.AiUsageLogEntity
import com.example.mypasture.ai.domain.GroundedAnswer
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** AI 사용량 로그를 PostgreSQL과 개발용 H2에서 동일하게 저장하는 repository입니다. */
interface AiUsageLogRepository : JpaRepository<AiUsageLogEntity, UUID> {
    fun countByUserIdAndCreatedAtGreaterThanEqual(userId: UUID, createdAt: Instant): Long
}

/** JPA로 AI 사용량을 저장하고 사용자의 UTC 기준 일일 요청 횟수를 조회합니다. */
@Repository
class JdbcAiUsageAdapter(
    private val repository: AiUsageLogRepository,
    private val properties: com.example.mypasture.config.AiProperties,
) : AiUsagePort {
    override fun countToday(userId: UUID): Long =
        repository.countByUserIdAndCreatedAtGreaterThanEqual(
            userId,
            LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC),
        )

    override fun save(userId: UUID, question: String, answer: GroundedAnswer) {
        repository.save(
            AiUsageLogEntity(
                userId = userId,
                requestHash = hash(question),
                model = properties.model,
                promptTokens = answer.promptTokens,
                completionTokens = answer.completionTokens,
                embeddingTokens = answer.embeddingTokens,
                embeddingCostUsd = answer.embeddingCostUsd,
                embeddingModel = properties.embeddingModel,
                retrievalStrategy = answer.retrievalStrategy.name,
                questionIntent = answer.questionIntent.name,
                estimatedCostUsd = answer.estimatedCostUsd,
                cacheHit = answer.cacheHit,
            ),
        )
    }

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.lowercase().trim().toByteArray())
            .joinToString("") { "%02x".format(it) }
}
