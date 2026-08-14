package com.example.mypasture.notification.domain

import com.example.mypasture.daily_word.domain.DailyWordEntity
import jakarta.persistence.*
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

enum class DevicePlatform { IOS, ANDROID }
enum class DeliveryStatus { SENT, FAILED }

/** 사용자 기기의 FCM 등록 토큰과 활성 상태를 저장합니다. */
@Entity @Table(name = "notification_devices", uniqueConstraints = [UniqueConstraint(columnNames = ["user_id", "device_id"])])
class NotificationDeviceEntity(@Id val id: UUID = UUID.randomUUID(), @Column(name = "user_id") val userId: UUID, @Column(name = "device_id") val deviceId: String, var fcmToken: String, @Enumerated(EnumType.STRING) val platform: DevicePlatform, var active: Boolean = true, var updatedAt: Instant = Instant.now())

/** 사용자별 오늘의 말씀 알림 시각과 시간대를 저장합니다. */
@Entity @Table(name = "daily_word_notification_settings")
class DailyWordNotificationSettingEntity(@Id val id: UUID = UUID.randomUUID(), @Column(name = "user_id", unique = true) val userId: UUID, var enabled: Boolean = true, var notificationTime: LocalTime = LocalTime.of(7, 0), var timezone: String = "Asia/Seoul", var updatedAt: Instant = Instant.now())

/** 사용자와 오늘의 말씀 조합별 푸시 발송 결과를 저장해 중복 발송을 방지합니다. */
@Entity @Table(name = "notification_deliveries", uniqueConstraints = [UniqueConstraint(columnNames = ["user_id", "daily_word_id"])])
class NotificationDeliveryEntity(@Id val id: UUID = UUID.randomUUID(), @Column(name = "user_id") val userId: UUID, @ManyToOne(fetch = FetchType.LAZY) val dailyWord: DailyWordEntity, @Enumerated(EnumType.STRING) val status: DeliveryStatus, val errorMessage: String? = null, val sentAt: Instant? = null, val createdAt: Instant = Instant.now())
