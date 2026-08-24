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

/** 앱이 허용하는 알림 종류입니다. 임의 URL 대신 enum으로 화면 이동 범위를 제한합니다. */
enum class NotificationType { DAILY_WORD }

/** 모바일에서 해석할 수 있는 타입 기반 이동 목적지입니다. */
enum class NotificationDestinationType { TODAY_WORD }

/** 모든 사용자에게 공통인 콘텐츠를 한 행으로 저장해 대량 fan-out 저장을 피합니다. */
@Entity
@Table(name = "notification_broadcasts")
class NotificationBroadcastEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "event_key", nullable = false, unique = true, length = 160) val eventKey: String,
    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false, length = 40) val type: NotificationType,
    @Column(nullable = false, length = 120) val title: String,
    @Column(nullable = false, length = 500) val body: String,
    @Enumerated(EnumType.STRING)
    @Column(name = "destination_type", nullable = false, length = 40) val destinationType: NotificationDestinationType,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "daily_word_id") val dailyWord: DailyWordEntity? = null,
    @Column(name = "published_at", nullable = false) val publishedAt: Instant,
    @Column(name = "expires_at", nullable = false) val expiresAt: Instant,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = publishedAt,
)

/** 사용자가 특정 공통 알림을 읽은 사실만 희소하게 저장합니다. */
@Entity
@Table(
    name = "notification_read_receipts",
    uniqueConstraints = [UniqueConstraint(columnNames = ["broadcast_id", "user_id"])],
)
class NotificationReadReceiptEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "broadcast_id", nullable = false) val broadcast: NotificationBroadcastEntity,
    @Column(name = "user_id", nullable = false) val userId: UUID,
    @Column(name = "read_at", nullable = false) var readAt: Instant,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = readAt,
)

/** 전체 읽음은 사용자마다 watermark 한 행만 갱신해 receipt 폭증을 막습니다. */
@Entity
@Table(name = "notification_read_watermarks")
class NotificationReadWatermarkEntity(
    @Id @Column(name = "user_id") val userId: UUID,
    @Column(name = "read_at", nullable = false) var readAt: Instant,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = readAt,
)

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
