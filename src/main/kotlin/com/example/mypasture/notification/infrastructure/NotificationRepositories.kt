package com.example.mypasture.notification.infrastructure

import com.example.mypasture.notification.domain.DailyWordNotificationSettingEntity
import com.example.mypasture.notification.domain.DeliveryStatus
import com.example.mypasture.notification.domain.NotificationDeliveryEntity
import com.example.mypasture.notification.domain.NotificationDeviceEntity
import com.example.mypasture.notification.domain.NotificationBroadcastEntity
import com.example.mypasture.notification.domain.NotificationReadReceiptEntity
import com.example.mypasture.notification.domain.NotificationReadWatermarkEntity
import com.example.mypasture.notification.domain.NotificationType
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

    @Query(
        """
        select delivery.status as status, count(delivery) as total
        from NotificationDeliveryEntity delivery
        where delivery.createdAt >= :from and delivery.createdAt < :to
        group by delivery.status
        """,
    )
    fun summarizeByCreatedAt(from: Instant, to: Instant): List<NotificationDeliveryStatusCount>

    @Query(
        """
        select delivery.dailyWord.id as dailyWordId, delivery.status as status, count(delivery) as total
        from NotificationDeliveryEntity delivery
        where delivery.dailyWord.id in :dailyWordIds
        group by delivery.dailyWord.id, delivery.status
        """,
    )
    fun summarizeByDailyWordIds(dailyWordIds: Collection<UUID>): List<NotificationDailyWordDeliveryCount>

    @Query(
        """
        select count(delivery) as total,
               coalesce(sum(case when delivery.receivedAt is not null then 1 else 0 end), 0) as receivedByApp,
               coalesce(sum(case when delivery.openedAt is not null then 1 else 0 end), 0) as openedFromPush
        from NotificationDeliveryEntity delivery
        where delivery.createdAt >= :from and delivery.createdAt < :to
        """,
    )
    fun summarizeLifecycleByCreatedAt(from: Instant, to: Instant): NotificationDeliveryLifecycleCount

    @Query(
        """
        select delivery.dailyWord.id as dailyWordId,
               count(delivery) as total,
               coalesce(sum(case when delivery.receivedAt is not null then 1 else 0 end), 0) as receivedByApp,
               coalesce(sum(case when delivery.openedAt is not null then 1 else 0 end), 0) as openedFromPush
        from NotificationDeliveryEntity delivery
        where delivery.dailyWord.id in :dailyWordIds
        group by delivery.dailyWord.id
        """,
    )
    fun summarizeLifecycleByDailyWordIds(dailyWordIds: Collection<UUID>): List<NotificationDailyWordLifecycleCount>
}

/** 공통 알림을 cursor 순서로 조회하고 관리자 기간 필터를 제공합니다. */
interface NotificationBroadcastRepository : JpaRepository<NotificationBroadcastEntity, UUID> {
    fun existsByEventKey(eventKey: String): Boolean

    @Query(
        """
        select broadcast.dailyWord.id as dailyWordId, broadcast.id as broadcastId
        from NotificationBroadcastEntity broadcast
        where broadcast.dailyWord.id in :dailyWordIds
        """,
    )
    fun findIdsByDailyWordIds(dailyWordIds: Collection<UUID>): List<NotificationDailyWordBroadcastId>

    @Query(
        """
        select broadcast from NotificationBroadcastEntity broadcast
        join fetch broadcast.dailyWord
        where broadcast.publishedAt <= :now
          and broadcast.expiresAt > :now
          and broadcast.publishedAt >= :visibleFrom
        order by broadcast.publishedAt desc, broadcast.id desc
        """,
    )
    fun findVisibleFirstPage(now: Instant, visibleFrom: Instant, pageable: Pageable): List<NotificationBroadcastEntity>

    @Query(
        """
        select broadcast from NotificationBroadcastEntity broadcast
        join fetch broadcast.dailyWord
        where broadcast.publishedAt <= :now
          and broadcast.expiresAt > :now
          and broadcast.publishedAt >= :visibleFrom
          and (broadcast.publishedAt < :cursorAt or (broadcast.publishedAt = :cursorAt and broadcast.id < :cursorId))
        order by broadcast.publishedAt desc, broadcast.id desc
        """,
    )
    fun findVisibleAfter(
        now: Instant,
        visibleFrom: Instant,
        cursorAt: Instant,
        cursorId: UUID,
        pageable: Pageable,
    ): List<NotificationBroadcastEntity>

    @Query(
        """
        select broadcast from NotificationBroadcastEntity broadcast
        join fetch broadcast.dailyWord
        where broadcast.id = :id and broadcast.publishedAt <= :now and broadcast.expiresAt > :now
        """,
    )
    fun findVisibleById(id: UUID, now: Instant): NotificationBroadcastEntity?

    @Query(
        """
        select count(broadcast) from NotificationBroadcastEntity broadcast
        where broadcast.publishedAt <= :now
          and broadcast.expiresAt > :now
          and broadcast.publishedAt >= :visibleFrom
          and broadcast.publishedAt > :watermark
          and not exists (
            select receipt.id from NotificationReadReceiptEntity receipt
            where receipt.broadcast.id = broadcast.id and receipt.userId = :userId
          )
        """,
    )
    fun countUnread(userId: UUID, now: Instant, visibleFrom: Instant, watermark: Instant): Long

    @Query(
        value = """
        select broadcast from NotificationBroadcastEntity broadcast
        join fetch broadcast.dailyWord
        where (:type is null or broadcast.type = :type)
          and broadcast.publishedAt >= :from and broadcast.publishedAt < :to
        order by broadcast.publishedAt desc, broadcast.id desc
        """,
        countQuery = """
        select count(broadcast) from NotificationBroadcastEntity broadcast
        where (:type is null or broadcast.type = :type)
          and broadcast.publishedAt >= :from and broadcast.publishedAt < :to
        """,
    )
    fun findForAdmin(type: NotificationType?, from: Instant, to: Instant, pageable: Pageable): Page<NotificationBroadcastEntity>

    fun countByPublishedAtGreaterThanEqualAndPublishedAtLessThan(from: Instant, to: Instant): Long
}

/** 단건 읽음과 관리자 열람 집계를 저장합니다. */
interface NotificationReadReceiptRepository : JpaRepository<NotificationReadReceiptEntity, UUID> {
    fun findByBroadcast_IdAndUserId(broadcastId: UUID, userId: UUID): NotificationReadReceiptEntity?
    fun findAllByUserIdAndBroadcast_IdIn(userId: UUID, broadcastIds: Collection<UUID>): List<NotificationReadReceiptEntity>
    fun countByBroadcast_PublishedAtGreaterThanEqualAndBroadcast_PublishedAtLessThan(from: Instant, to: Instant): Long

    @Query(
        """
        select receipt.broadcast.id as broadcastId, count(receipt) as total
        from NotificationReadReceiptEntity receipt
        where receipt.broadcast.id in :broadcastIds
        group by receipt.broadcast.id
        """,
    )
    fun countByBroadcastIds(broadcastIds: Collection<UUID>): List<NotificationBroadcastReadCount>
}

/** 사용자별 전체 읽음 watermark를 직렬화해 갱신합니다. */
interface NotificationReadWatermarkRepository : JpaRepository<NotificationReadWatermarkEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select watermark from NotificationReadWatermarkEntity watermark where watermark.userId = :userId")
    fun findByUserIdForUpdate(userId: UUID): NotificationReadWatermarkEntity?
}

interface NotificationDeliveryStatusCount { val status: DeliveryStatus; val total: Long }
interface NotificationDailyWordDeliveryCount { val dailyWordId: UUID; val status: DeliveryStatus; val total: Long }
interface NotificationDeliveryLifecycleCount { val total: Long; val receivedByApp: Long; val openedFromPush: Long }
interface NotificationDailyWordLifecycleCount {
    val dailyWordId: UUID
    val total: Long
    val receivedByApp: Long
    val openedFromPush: Long
}
interface NotificationBroadcastReadCount { val broadcastId: UUID; val total: Long }
interface NotificationDailyWordBroadcastId { val dailyWordId: UUID; val broadcastId: UUID }
