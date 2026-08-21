package com.example.mypasture.config

import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/** QT 답변 SSE 연결 수명과 다중 서버 Redis 전달 여부를 관리합니다. */
@Validated
@ConfigurationProperties("app.quiet-time.realtime")
data class QuietTimeRealtimeProperties(
    val redisEnabled: Boolean = false,
    @field:Min(10_000)
    val sseTimeoutMs: Long = 30 * 60 * 1000,
    @field:Min(5_000)
    val heartbeatDelayMs: Long = 15_000,
)
