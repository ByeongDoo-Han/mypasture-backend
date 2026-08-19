package com.example.mypasture.notification.infrastructure

import com.example.mypasture.notification.domain.*
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface NotificationDeviceRepository : JpaRepository<NotificationDeviceEntity, UUID> {
    fun findByUserIdAndDeviceId(userId: UUID, deviceId: String): NotificationDeviceEntity?
    fun findByFcmToken(fcmToken: String): NotificationDeviceEntity?
    fun findAllByUserIdAndActiveTrue(userId: UUID): List<NotificationDeviceEntity>
    fun deleteByUserIdAndDeviceId(userId: UUID, deviceId: String)
}
interface DailyWordNotificationSettingRepository : JpaRepository<DailyWordNotificationSettingEntity, UUID> {
    fun findByUserId(userId: UUID): DailyWordNotificationSettingEntity?
    fun findAllByEnabledTrue(): List<DailyWordNotificationSettingEntity>
}
interface NotificationDeliveryRepository : JpaRepository<NotificationDeliveryEntity, UUID> {
    fun existsByUserIdAndDailyWordId(userId: UUID, dailyWordId: UUID): Boolean
    fun findByUserIdAndDailyWordId(userId: UUID, dailyWordId: UUID): NotificationDeliveryEntity?
}
