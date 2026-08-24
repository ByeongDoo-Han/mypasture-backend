package com.example.mypasture.notification.infrastructure

import com.example.mypasture.notification.application.NotificationService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** 전용 worker 인스턴스에서만 실행되어 예약된 푸시 알림을 선점하고 발송합니다. */
@Component
@ConditionalOnProperty(prefix = "app.worker", name = ["enabled"], havingValue = "true")
class NotificationDispatchJob(
    private val notificationService: NotificationService,
) {
    @Scheduled(cron = "0 * * * * *")
    suspend fun dispatch() {
        notificationService.dispatchDueDailyWords()
    }
}
