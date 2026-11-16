package com.example.mypasture.prayer.infrastructure

import com.example.mypasture.notification.application.PushSender
import com.example.mypasture.notification.infrastructure.NotificationDeviceRepository
import com.example.mypasture.prayer.domain.PrayerNotificationEntity
import com.example.mypasture.prayer.infrastructure.PrayerProfileRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** 기도 알림을 DB lease로 선점하고 기기 푸시와 일요일 알림을 재시도합니다. */
@Service
class PrayerNotificationDispatchService(
    private val notifications: PrayerNotificationRepository,
    private val profiles: PrayerProfileRepository,
    private val devices: NotificationDeviceRepository,
    private val sender: PushSender,
    private val transactions: TransactionTemplate,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun reserveSundayReminders() {
        val seoul = ZoneId.of("Asia/Seoul")
        val now = Instant.now(clock)
        val local = now.atZone(seoul)
        if (local.dayOfWeek.value != 7 || local.hour !in 9..11) return
        val week = LocalDate.now(clock.withZone(seoul))
        profiles.findAllBySundayReminderEnabledTrue().forEach { profile ->
            val key = "prayer-sunday:${week}:${profile.userId}"
            if (!notifications.existsByEventKey(key))
                notifications.save(PrayerNotificationEntity(eventKey = key, userId = profile.userId,
                    kind = "SUNDAY_REMINDER", title = "이번 주 기도제목을 기록해 보세요",
                    body = "이번 주 마음에 담긴 기도제목을 적어 보세요.", createdAt = now, nextAttemptAt = now))
        }
    }

    @Transactional
    fun claimDue(): List<UUID> {
        val now = Instant.now(clock)
        val items = notifications.findDueForUpdate(now, PageRequest.of(0, 50))
        items.forEach {
            it.deliveryStatus = "PROCESSING"
            it.leaseUntil = now.plus(Duration.ofMinutes(2))
            it.attemptCount += 1
        }
        return items.map { it.id }
    }

    suspend fun send(id: UUID) {
        val notification = notifications.findById(id).orElse(null) ?: return
        val ok = runCatching {
            devices.findAllByUserIdAndActiveTrue(notification.userId).forEach { device ->
                sender.send(device.fcmToken, notification.title, notification.body,
                    mapOf("type" to notification.kind, "prayerNotificationId" to notification.id.toString(),
                        "relatedId" to (notification.relatedId?.toString() ?: "")))
            }
        }.isSuccess
        complete(id, ok)
    }

    fun complete(id: UUID, success: Boolean) = transactions.executeWithoutResult {
        val item = notifications.findById(id).orElse(null) ?: return@executeWithoutResult
        if (item.deliveryStatus != "PROCESSING") return@executeWithoutResult
        val now = Instant.now(clock)
        item.leaseUntil = null
        if (success) item.deliveryStatus = "SENT"
        else if (item.attemptCount >= 5) item.deliveryStatus = "DEAD"
        else {
            item.deliveryStatus = "PENDING"
            item.nextAttemptAt = now.plusSeconds((30L shl (item.attemptCount - 1)).coerceAtMost(3600))
        }
    }
}

/** 전용 worker만 기도 편지 및 일요일 알림을 발송합니다. */
@Component
@ConditionalOnProperty(prefix = "app.worker", name = ["enabled"], havingValue = "true")
class PrayerNotificationDispatchJob(private val service: PrayerNotificationDispatchService) {
    @Scheduled(cron = "0 */15 * * * *")
    suspend fun dispatch() {
        service.reserveSundayReminders()
        repeat(10) {
            val claims = service.claimDue()
            if (claims.isEmpty()) return
            claims.forEach { service.send(it) }
        }
    }
}
