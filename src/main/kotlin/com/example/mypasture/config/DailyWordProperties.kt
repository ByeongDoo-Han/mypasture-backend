package com.example.mypasture.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration
import java.time.LocalTime

/** 오늘의 말씀 초안 생성 일정, provider와 작업 lease 정책을 바인딩합니다. */
@ConfigurationProperties("app.daily-word")
data class DailyWordProperties(
    val rotationEnabled: Boolean = false,
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
    val incidentDetectionEnabled: Boolean = false,
    val incidentDetectionCron: String = "0 */10 * * * *",
    val incidentWarningTime: LocalTime = LocalTime.of(20, 0),
    val incidentCriticalTime: LocalTime = LocalTime.of(22, 0),
    val incidentStalledThreshold: Duration = Duration.ofMinutes(10),
    val incidentEmailDelivery: String = "log",
    val incidentEmailFrom: String = "no-reply@mypasture.app",
    val incidentEmailPollDelayMs: Long = 30_000,
    val incidentEmailLeaseDuration: Duration = Duration.ofMinutes(2),
    val incidentEmailRetryDelay: Duration = Duration.ofMinutes(5),
    val incidentEmailMaxAttempts: Int = 5,
    val incidentEmailBatchSize: Int = 20,
) {
    init {
        require(incidentCriticalTime.isAfter(incidentWarningTime)) { "Incident critical time must be after warning time." }
        require(!incidentStalledThreshold.isNegative && !incidentStalledThreshold.isZero) { "Incident stalled threshold must be positive." }
        require(incidentEmailDelivery in setOf("log", "smtp", "disabled")) { "Incident email delivery must be log, smtp, or disabled." }
        require(incidentEmailPollDelayMs >= 1_000) { "Incident email poll delay must be at least 1000ms." }
        require(!incidentEmailLeaseDuration.isNegative && !incidentEmailLeaseDuration.isZero) { "Incident email lease duration must be positive." }
        require(!incidentEmailRetryDelay.isNegative && !incidentEmailRetryDelay.isZero) { "Incident email retry delay must be positive." }
        require(incidentEmailMaxAttempts in 1..20) { "Incident email max attempts must be between 1 and 20." }
        require(incidentEmailBatchSize in 1..100) { "Incident email batch size must be between 1 and 100." }
    }
}
