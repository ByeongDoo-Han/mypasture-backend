package com.example.mypasture.notification.infrastructure

import com.example.mypasture.notification.domain.DailyWordNotificationSettingEntity
import com.example.mypasture.notification.domain.DeliveryStatus
import com.example.mypasture.notification.domain.NotificationDeliveryEntity
import com.example.mypasture.notification.domain.NotificationDeviceEntity
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

/** 활성 기기 등록과 FCM 토큰의 단일 소유자 규칙을 영속화합니다. */
interface NotificationDeviceRepository : JpaRepository<NotificationDeviceEntity, UUID> {
    fun findByUserIdAndDeviceIdAndActiveTrue(userId: UUID, deviceId: String): NotificationDeviceEntity?
    fun findByFcmTokenAndActiveTrue(fcmToken: String): NotificationDeviceEntity?
    fun findAllByUserIdAndActiveTrue(userId: UUID): List<NotificationDeviceEntity>
}

/** 활성 알림 설정을 고정 크기 페이지로 읽어 전체 사용자 목록의 메모리 적재를 막습니다. */
interface DailyWordNotificationSettingRepository : JpaRepository<DailyWordNotificationSettingEntity, UUID> {
    fun findByUserId(userId: UUID): DailyWordNotificationSettingEntity?
    fun findAllByEnabledTrue(pageable: Pageable): Page<DailyWordNotificationSettingEntity>
}

/** 기기별 delivery를 예약하고, 만료 lease와 재시도 대기 작업을 DB 잠금으로 선점합니다. */
interface NotificationDeliveryRepository : JpaRepository<NotificationDeliveryEntity, UUID> {
    fun existsByDailyWord_IdAndDevice_Id(dailyWordId: UUID, deviceId: UUID): Boolean

    fun findByDailyWord_IdAndDevice_Id(dailyWordId: UUID, deviceId: UUID): NotificationDeliveryEntity?

    @Modifying(flushAutomatically = true)
    @Query(
        """
            update NotificationDeliveryEntity delivery
            set delivery.status = :deadStatus,
                delivery.errorCode = :errorCode,
                delivery.errorMessage = :errorMessage,
                delivery.leaseUntil = null,
                delivery.leaseToken = null,
                delivery.updatedAt = :now
            where delivery.device.id = :deviceId
              and delivery.status in :activeStatuses
        """,
    )
    fun deadLetterActiveForDevice(
        deviceId: UUID,
        activeStatuses: Set<DeliveryStatus>,
        deadStatus: DeliveryStatus,
        errorCode: String,
        errorMessage: String,
        now: Instant,
    ): Int

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
            select delivery from NotificationDeliveryEntity delivery
            join fetch delivery.device
            join fetch delivery.dailyWord
            where delivery.id = :deliveryId
        """,
    )
    fun findLockedById(deliveryId: UUID): NotificationDeliveryEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
            select delivery from NotificationDeliveryEntity delivery
            join fetch delivery.device device
            join fetch delivery.dailyWord
            where device.active = true
              and delivery.userId = device.userId
              and (
                (delivery.status in :readyStatuses and delivery.nextAttemptAt <= :now)
                or (delivery.status = :processingStatus and delivery.leaseUntil <= :now)
              )
            order by delivery.nextAttemptAt, delivery.createdAt, delivery.id
        """,
    )
    fun findClaimable(
        readyStatuses: Set<DeliveryStatus>,
        processingStatus: DeliveryStatus,
        now: Instant,
        pageable: Pageable,
    ): List<NotificationDeliveryEntity>
}
