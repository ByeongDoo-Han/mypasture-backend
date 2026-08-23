package com.example.mypasture.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** 푸시 작업의 lease, 재시도 횟수와 한 번에 처리할 작업량을 제한합니다. */
@ConfigurationProperties("app.notification.delivery")
data class NotificationDeliveryProperties(
    val maxAttempts: Int = 5,
    val leaseDuration: Duration = Duration.ofMinutes(2),
    val initialBackoff: Duration = Duration.ofSeconds(30),
    val maxBackoff: Duration = Duration.ofHours(1),
    val settingsPageSize: Int = 500,
    val dispatchBatchSize: Int = 100,
    val maxBatchesPerRun: Int = 20,
) {
    init {
        require(maxAttempts in 1..20) { "Notification maxAttempts must be between 1 and 20." }
        require(!leaseDuration.isNegative && !leaseDuration.isZero) { "Notification leaseDuration must be positive." }
        require(!initialBackoff.isNegative && !initialBackoff.isZero) { "Notification initialBackoff must be positive." }
        require(maxBackoff >= initialBackoff) { "Notification maxBackoff must be greater than or equal to initialBackoff." }
        require(settingsPageSize in 1..2_000) { "Notification settingsPageSize must be between 1 and 2000." }
        require(dispatchBatchSize in 1..500) { "Notification dispatchBatchSize must be between 1 and 500." }
        require(maxBatchesPerRun in 1..100) { "Notification maxBatchesPerRun must be between 1 and 100." }
    }
}
