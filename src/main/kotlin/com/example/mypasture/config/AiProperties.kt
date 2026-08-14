package com.example.mypasture.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigDecimal

/** `app.ai` 설정값을 타입 안전하게 바인딩하는 configuration properties 클래스입니다. */
@ConfigurationProperties("app.ai")
data class AiProperties(
    val model: String,
    val embeddingModel: String,
    val apiKey: String,
    val baseUrl: String,
    val maxInputChars: Int,
    val maxOutputTokens: Int,
    val freeDailyLimit: Int,
    val timeoutMs: Long,
    val cacheTtlSeconds: Long,
    val commentaryGenerationEnabled: Boolean,
    val commentaryPollDelayMs: Long,
    val commentaryPromptVersion: String,
    val commentaryMaxOutputTokens: Int,
    val commentaryInputCostPerMillion: BigDecimal,
    val commentaryOutputCostPerMillion: BigDecimal,
)
