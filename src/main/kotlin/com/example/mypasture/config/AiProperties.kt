package com.example.mypasture.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigDecimal

/** `app.ai` 설정값을 타입 안전하게 바인딩하는 configuration properties 클래스입니다. */
@ConfigurationProperties("app.ai")
data class AiProperties(
    val model: String,
    val embeddingModel: String,
    val embeddingDimensions: Int,
    val embeddingBatchSize: Int,
    val embeddingIndexingEnabled: Boolean,
    val embeddingIndexPollDelayMs: Long,
    val embeddingBackfillEnabled: Boolean = false,
    val embeddingBackfillMaxBatches: Int = 1_000,
    val embeddingBackfillDelayMs: Long = 100,
    val embeddingResponseMaxBytes: Int = 16 * 1024 * 1024,
    val embeddingInputCostPerMillion: BigDecimal,
    val apiKey: String,
    val baseUrl: String,
    val maxInputChars: Int,
    val maxOutputTokens: Int,
    val freeDailyLimit: Int,
    val premiumDailySafetyLimit: Int = 500,
    val timeoutMs: Long,
    val cacheTtlSeconds: Long,
    val ragTopK: Int,
    val ragMinSimilarity: Double,
    val ragLexicalFallbackEnabled: Boolean,
    val commentaryGenerationEnabled: Boolean,
    val commentaryPollDelayMs: Long,
    val commentaryPromptVersion: String,
    val commentaryMaxOutputTokens: Int,
    val commentaryInputCostPerMillion: BigDecimal,
    val commentaryOutputCostPerMillion: BigDecimal,
    val streamTimeoutMs: Long = 60_000,
    val streamCorePoolSize: Int = 8,
    val streamMaxPoolSize: Int = 64,
    val streamQueueCapacity: Int = 200,
    val reasoningEffort: String = "minimal",
    val privacyHashSecret: String = "local-ai-privacy-secret-change-me",
    val generatorModel: String = model,
    val evaluatorModel: String = "gpt-5.6-luna",
    val evaluatorReasoningEffort: String = "none",
    val evaluationEnabled: Boolean = true,
    val evaluationThreshold: Int = 4,
    val evaluationMaxOutputTokens: Int = 500,
    val maxRevisionCount: Int = 1,
    val idempotencyResponseTtlSeconds: Long = 86_400,
    val idempotencyReceiptRetentionDays: Long = 30,
    val idempotencyPendingTimeoutMs: Long = 120_000,
) {
    init {
        require(idempotencyResponseTtlSeconds in 60..604_800) {
            "AI idempotency response TTL must be between 60 seconds and 7 days."
        }
        require(idempotencyReceiptRetentionDays in 1..365) {
            "AI idempotency receipt retention must be between 1 and 365 days."
        }
        require(idempotencyReceiptRetentionDays * 86_400 >= idempotencyResponseTtlSeconds) {
            "AI idempotency receipt retention must not be shorter than the response TTL."
        }
        require(idempotencyPendingTimeoutMs in streamTimeoutMs..86_400_000) {
            "AI idempotency pending timeout must be at least the stream timeout and no more than 24 hours."
        }
    }
}
