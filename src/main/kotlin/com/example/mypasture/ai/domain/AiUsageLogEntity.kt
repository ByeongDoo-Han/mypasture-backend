package com.example.mypasture.ai.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

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
    @Column(name = "prompt_tokens", nullable = false)
    val promptTokens: Int,
    @Column(name = "completion_tokens", nullable = false)
    val completionTokens: Int,
    @Column(name = "embedding_tokens", nullable = false)
    val embeddingTokens: Int = 0,
    @Column(name = "embedding_cost_usd", nullable = false, precision = 12, scale = 6)
    val embeddingCostUsd: BigDecimal = BigDecimal.ZERO,
    @Column(name = "embedding_model", length = 80)
    val embeddingModel: String? = null,
    @Column(name = "retrieval_strategy", nullable = false, length = 40)
    val retrievalStrategy: String = "LEXICAL_FALLBACK",
    @Column(name = "question_intent", nullable = false, length = 40)
    val questionIntent: String = "BIBLE_QA",
    @Column(name = "estimated_cost_usd", nullable = false, precision = 12, scale = 6)
    val estimatedCostUsd: BigDecimal,
    @Column(name = "cache_hit", nullable = false)
    val cacheHit: Boolean,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
