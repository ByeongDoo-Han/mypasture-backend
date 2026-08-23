package com.example.mypasture.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** 오늘의 말씀 초안 생성 일정, provider와 작업 lease 정책을 바인딩합니다. */
@ConfigurationProperties("app.daily-word")
data class DailyWordProperties(
    val generationEnabled: Boolean = false,
    val provider: String = "disabled",
    val generationCron: String = "0 0 18 * * *",
    val generationZone: String = "Asia/Seoul",
    val targetDaysAhead: Long = 1,
    val pollDelayMs: Long = 5_000,
    val leaseDuration: Duration = Duration.ofMinutes(2),
    val maxAttempts: Int = 3,
    val promptVersion: String = "daily-word-v1",
    val maxOutputTokens: Int = 500,
)
