package com.example.mypasture.ai.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

enum class AiUsageStatus {
    PENDING,
    COMPLETED,
    FAILED,
}

/** AI 요청의 토큰 사용량, 비용, 캐시 적중 여부를 영구 저장하는 엔티티입니다. */
@Entity
@Table(
    name = "ai_usage_logs",
    indexes = [Index(name = "ix_ai_usage_user_created", columnList = "user_id,created_at")],
)
class AiUsageLogEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "user_id", nullable = false)
    val userId: UUID,
    @Column(name = "request_hash", nullable = false, length = 128)
    val requestHash: String,
    @Column(nullable = false, length = 80)
    val model: String,
    @Column(name = "model_config_version_id")
    val modelConfigVersionId: UUID? = null,
    @Column(name = "evaluator_model", length = 80)
    val evaluatorModel: String? = null,
    @Column(name = "prompt_tokens", nullable = false)
    var promptTokens: Int = 0,
    @Column(name = "completion_tokens", nullable = false)
    var completionTokens: Int = 0,
    @Column(name = "evaluation_prompt_tokens", nullable = false)
    var evaluationPromptTokens: Int = 0,
    @Column(name = "evaluation_completion_tokens", nullable = false)
    var evaluationCompletionTokens: Int = 0,
    @Column(name = "evaluation_cost_usd", nullable = false, precision = 12, scale = 6)
    var evaluationCostUsd: BigDecimal = BigDecimal.ZERO,
    @Column(name = "evaluation_passed")
    var evaluationPassed: Boolean? = null,
    @Column(name = "evaluation_grounding_score")
    var evaluationGroundingScore: Int? = null,
    @Column(name = "evaluation_citation_score")
    var evaluationCitationScore: Int? = null,
    @Column(name = "evaluation_safety_score")
    var evaluationSafetyScore: Int? = null,
    @Column(name = "evaluation_relevance_score")
    var evaluationRelevanceScore: Int? = null,
    @Column(name = "evaluation_pastoral_tone_score")
    var evaluationPastoralToneScore: Int? = null,
    @Column(name = "evaluation_issues", columnDefinition = "text")
    var evaluationIssues: String? = null,
    @Column(name = "revision_count", nullable = false)
    var revisionCount: Int = 0,
    @Column(name = "embedding_tokens", nullable = false)
    var embeddingTokens: Int = 0,
    @Column(name = "embedding_cost_usd", nullable = false, precision = 12, scale = 6)
    var embeddingCostUsd: BigDecimal = BigDecimal.ZERO,
    @Column(name = "embedding_model", length = 80)
    var embeddingModel: String? = null,
    @Column(name = "retrieval_strategy", nullable = false, length = 40)
    var retrievalStrategy: String = "LEXICAL_FALLBACK",
    @Column(name = "question_intent", nullable = false, length = 40)
    var questionIntent: String = "BIBLE_QA",
    @Column(name = "estimated_cost_usd", nullable = false, precision = 12, scale = 6)
    var estimatedCostUsd: BigDecimal = BigDecimal.ZERO,
    @Column(name = "cache_hit", nullable = false)
    var cacheHit: Boolean = false,
    @Column(name = "quota_counted", nullable = false)
    val quotaCounted: Boolean = true,
    @Column(name = "parent_turn_id")
    val parentTurnId: UUID? = null,
    @Column(name = "citation_verse_ids", columnDefinition = "text")
    var citationVerseIds: String? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: AiUsageStatus = AiUsageStatus.PENDING,
    @Column(name = "error_code", length = 80)
    var errorCode: String? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
) {
    fun complete(answer: GroundedAnswer, embeddingModel: String) {
        promptTokens = answer.promptTokens
        completionTokens = answer.completionTokens
        evaluationPromptTokens = answer.evaluationPromptTokens
        evaluationCompletionTokens = answer.evaluationCompletionTokens
        evaluationCostUsd = answer.evaluationCostUsd
        evaluationPassed = answer.evaluationPassed
        evaluationGroundingScore = answer.evaluationGroundingScore
        evaluationCitationScore = answer.evaluationCitationScore
        evaluationSafetyScore = answer.evaluationSafetyScore
        evaluationRelevanceScore = answer.evaluationRelevanceScore
        evaluationPastoralToneScore = answer.evaluationPastoralToneScore
        evaluationIssues = answer.evaluationIssues.joinToString("\n").takeIf { it.isNotBlank() }
        revisionCount = answer.revisionCount
        embeddingTokens = answer.embeddingTokens
        embeddingCostUsd = answer.embeddingCostUsd
        this.embeddingModel = embeddingModel
        retrievalStrategy = answer.retrievalStrategy.name
        questionIntent = answer.questionIntent.name
        estimatedCostUsd = answer.estimatedCostUsd
        cacheHit = answer.cacheHit
        citationVerseIds = answer.citations.joinToString(",") { it.verseId.toString() }.takeIf { it.isNotBlank() }
        status = AiUsageStatus.COMPLETED
        errorCode = null
    }

    fun fail(code: String) {
        status = AiUsageStatus.FAILED
        errorCode = code.take(80)
    }
}
