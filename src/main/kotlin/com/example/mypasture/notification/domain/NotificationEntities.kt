package com.example.mypasture.notification.domain

import com.example.mypasture.daily_word.domain.DailyWordEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

enum class DevicePlatform { IOS, ANDROID }

/** 기기별 푸시 작업이 현재 어느 처리 단계에 있는지 나타냅니다. */
enum class DeliveryStatus { PENDING, PROCESSING, RETRY_WAIT, SENT, DEAD }

/** 사용자 기기의 FCM 등록 토큰과 활성 상태를 저장합니다. */
@Entity
@Table(name = "notification_devices")
class NotificationDeviceEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "user_id", nullable = false) var userId: UUID,
    @Column(name = "device_id", nullable = false) var deviceId: String,
    @Column(name = "fcm_token", nullable = false) var fcmToken: String,
    @Enumerated(EnumType.STRING) var platform: DevicePlatform,
    var active: Boolean = true,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now(),
)

/** 사용자별 오늘의 말씀 알림 시각과 시간대를 저장합니다. */
@Entity
@Table(name = "daily_word_notification_settings")
class DailyWordNotificationSettingEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "user_id", unique = true, nullable = false) val userId: UUID,
    var enabled: Boolean = true,
    var notificationTime: LocalTime = LocalTime.of(7, 0),
    var timezone: String = "Asia/Seoul",
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now(),
)

/** 한 기기에 대한 푸시 발송을 lease와 fencing token으로 안전하게 처리하는 작업 원장입니다. */
@Entity
@Table(
    name = "notification_device_deliveries",
    uniqueConstraints = [UniqueConstraint(columnNames = ["daily_word_id", "device_id"])],
)
class NotificationDeliveryEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "user_id", nullable = false) val userId: UUID,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "daily_word_id", nullable = false)
    val dailyWord: DailyWordEntity,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_id", nullable = false)
    val device: NotificationDeviceEntity,
    @Enumerated(EnumType.STRING)
    var status: DeliveryStatus = DeliveryStatus.PENDING,
    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int = 0,
    @Column(name = "next_attempt_at", nullable = false)
    var nextAttemptAt: Instant,
    @Column(name = "lease_until")
    var leaseUntil: Instant? = null,
    @Column(name = "lease_token")
    var leaseToken: UUID? = null,
    @Column(name = "error_code")
    var errorCode: String? = null,
    @Column(name = "error_message")
    var errorMessage: String? = null,
    @Column(name = "sent_at")
    var sentAt: Instant? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt,
)
