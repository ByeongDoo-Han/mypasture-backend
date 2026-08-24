package com.example.mypasture.notification.application

import com.example.mypasture.auth.infrastructure.UserRepository
import com.example.mypasture.notification.domain.DeliveryStatus
import com.example.mypasture.notification.domain.NotificationBroadcastEntity
import com.example.mypasture.notification.domain.NotificationDestinationType
import com.example.mypasture.notification.domain.NotificationReadReceiptEntity
import com.example.mypasture.notification.domain.NotificationReadWatermarkEntity
import com.example.mypasture.notification.domain.NotificationType
import com.example.mypasture.notification.infrastructure.NotificationBroadcastRepository
import com.example.mypasture.notification.infrastructure.NotificationDeliveryRepository
import com.example.mypasture.notification.infrastructure.NotificationReadReceiptRepository
import com.example.mypasture.notification.infrastructure.NotificationReadWatermarkRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import java.util.UUID

/** 공통 broadcast와 사용자별 읽음 상태를 결합해 확장 가능한 인앱 알림 목록을 제공합니다. */
@Service
@Transactional(readOnly = true)
class NotificationInboxService(
    private val userRepository: UserRepository,
    private val broadcastRepository: NotificationBroadcastRepository,
    private val receiptRepository: NotificationReadReceiptRepository,
    private val watermarkRepository: NotificationReadWatermarkRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun list(userId: UUID, cursor: String?, limit: Int): NotificationInboxPage {
        require(limit in 1..50) { "알림 조회 개수는 1에서 50 사이여야 합니다." }
        val user = activeUser(userId)
        val now = Instant.now(clock)
        val cursorValue = cursor?.let(::decodeCursor)
        val rows = if (cursorValue == null) {
            broadcastRepository.findVisibleFirstPage(now, user.createdAt, PageRequest.of(0, limit + 1))
        } else {
            broadcastRepository.findVisibleAfter(now, user.createdAt, cursorValue.publishedAt, cursorValue.id, PageRequest.of(0, limit + 1))
        }
        val hasNext = rows.size > limit
        val pageRows = rows.take(limit)
        val readIds = if (pageRows.isEmpty()) emptySet() else receiptRepository
            .findAllByUserIdAndBroadcast_IdIn(userId, pageRows.map { it.id })
            .mapTo(mutableSetOf()) { it.broadcast.id }
        val watermark = watermarkRepository.findById(userId).orElse(null)?.readAt ?: Instant.EPOCH
        return NotificationInboxPage(
            items = pageRows.map { it.toItem(it.id in readIds || !it.publishedAt.isAfter(watermark)) },
            nextCursor = if (hasNext) pageRows.lastOrNull()?.let(::encodeCursor) else null,
            hasNext = hasNext,
        )
    }

    fun unreadCount(userId: UUID): Long {
        val user = activeUser(userId)
        val now = Instant.now(clock)
        val watermark = watermarkRepository.findById(userId).orElse(null)?.readAt ?: Instant.EPOCH
        return broadcastRepository.countUnread(userId, now, user.createdAt, watermark)
    }

    @Transactional
    fun markRead(userId: UUID, notificationId: UUID): NotificationInboxItem {
        val user = userRepository.findActiveByIdForUpdate(userId) ?: throw NotificationNotFoundException()
        val now = Instant.now(clock)
        val broadcast = broadcastRepository.findVisibleById(notificationId, now)
            ?.takeIf { !it.publishedAt.isBefore(user.createdAt) }
            ?: throw NotificationNotFoundException()
        val watermark = watermarkRepository.findById(userId).orElse(null)?.readAt ?: Instant.EPOCH
        if (broadcast.publishedAt.isAfter(watermark) && receiptRepository.findByBroadcast_IdAndUserId(broadcast.id, userId) == null) {
            receiptRepository.save(NotificationReadReceiptEntity(broadcast = broadcast, userId = userId, readAt = now))
        }
        return broadcast.toItem(true)
    }

    @Transactional
    fun markAllRead(userId: UUID): NotificationUnreadCount {
        userRepository.findActiveByIdForUpdate(userId) ?: throw NotificationNotFoundException()
        val now = Instant.now(clock)
        val watermark = watermarkRepository.findByUserIdForUpdate(userId)
            ?: NotificationReadWatermarkEntity(userId = userId, readAt = now)
        if (watermark.readAt.isBefore(now)) watermark.readAt = now
        watermark.updatedAt = now
        watermarkRepository.save(watermark)
        return NotificationUnreadCount(0)
    }

    private fun activeUser(userId: UUID) = userRepository.findByIdAndDeletedAtIsNull(userId) ?: throw NotificationNotFoundException()

    private fun NotificationBroadcastEntity.toItem(read: Boolean): NotificationInboxItem {
        val word = dailyWord ?: throw IllegalStateException("오늘의 말씀 알림에 게시 콘텐츠가 없습니다.")
        return NotificationInboxItem(
            id = id,
            type = type,
            title = title,
            body = body,
            destination = NotificationDestination(
                type = destinationType,
                dailyWordId = word.id,
                date = word.wordDate,
                version = word.bibleVersion,
                bookCode = word.bookCode,
                bookName = word.bookName,
                chapter = word.chapter,
                verse = word.verse,
            ),
            read = read,
            publishedAt = publishedAt,
        )
    }

    private fun encodeCursor(broadcast: NotificationBroadcastEntity): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString("${broadcast.publishedAt}|${broadcast.id}".toByteArray(StandardCharsets.UTF_8))

    private fun decodeCursor(cursor: String): NotificationCursor = try {
        val decoded = String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
        val separator = decoded.lastIndexOf('|')
        require(separator > 0)
        NotificationCursor(Instant.parse(decoded.substring(0, separator)), UUID.fromString(decoded.substring(separator + 1)))
    } catch (_: RuntimeException) {
        throw NotificationInvalidCursorException()
    }
}

/** 관리자에게 provider 접수와 앱 열람을 분리해 보여주는 읽기 전용 집계 서비스입니다. */
@Service
@Transactional(readOnly = true)
class NotificationOperationsService(
    private val broadcastRepository: NotificationBroadcastRepository,
    private val deliveryRepository: NotificationDeliveryRepository,
    private val receiptRepository: NotificationReadReceiptRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun operations(type: NotificationType?, from: Instant?, to: Instant?, page: Int, size: Int): NotificationOperations {
        require(page >= 0) { "페이지는 0 이상이어야 합니다." }
        require(size in 1..100) { "페이지 크기는 1에서 100 사이여야 합니다." }
        val end = to ?: Instant.now(clock).plusSeconds(1)
        val start = from ?: end.minus(DEFAULT_RANGE)
        require(start.isBefore(end)) { "조회 시작 시각은 종료 시각보다 빨라야 합니다." }
        require(Duration.between(start, end) <= MAX_RANGE) { "알림 운영 조회 기간은 최대 31일입니다." }

        val broadcasts = broadcastRepository.findForAdmin(type, start, end, PageRequest.of(page, size))
        val wordIds = broadcasts.content.mapNotNull { it.dailyWord?.id }
        val deliveryCounts = if (wordIds.isEmpty()) emptyMap() else deliveryRepository.summarizeByDailyWordIds(wordIds)
            .groupBy { it.dailyWordId }
            .mapValues { (_, values) -> values.associate { it.status to it.total } }
        val readCounts = if (broadcasts.isEmpty) emptyMap() else receiptRepository.countByBroadcastIds(broadcasts.content.map { it.id })
            .associate { it.broadcastId to it.total }
        val statusCounts = deliveryRepository.summarizeByCreatedAt(start, end).associate { it.status to it.total }

        return NotificationOperations(
            from = start,
            to = end,
            generatedAt = Instant.now(clock),
            summary = NotificationOperationsSummary(
                broadcasts = broadcastRepository.countByPublishedAtGreaterThanEqualAndPublishedAtLessThan(start, end),
                deviceDeliveries = statusCounts.values.sum(),
                pending = statusCounts[DeliveryStatus.PENDING] ?: 0,
                processing = statusCounts[DeliveryStatus.PROCESSING] ?: 0,
                retryWait = statusCounts[DeliveryStatus.RETRY_WAIT] ?: 0,
                sentToProvider = statusCounts[DeliveryStatus.SENT] ?: 0,
                dead = statusCounts[DeliveryStatus.DEAD] ?: 0,
                opened = receiptRepository.countByBroadcast_PublishedAtGreaterThanEqualAndBroadcast_PublishedAtLessThan(start, end),
            ),
            broadcasts = broadcasts.content.map { broadcast ->
                val counts = broadcast.dailyWord?.id?.let(deliveryCounts::get).orEmpty()
                NotificationBroadcastOperation(
                    id = broadcast.id,
                    type = broadcast.type,
                    title = broadcast.title,
                    body = broadcast.body,
                    destinationType = broadcast.destinationType,
                    publishedAt = broadcast.publishedAt,
                    expiresAt = broadcast.expiresAt,
                    deviceDeliveries = counts.values.sum(),
                    sentToProvider = counts[DeliveryStatus.SENT] ?: 0,
                    dead = counts[DeliveryStatus.DEAD] ?: 0,
                    opened = readCounts[broadcast.id] ?: 0,
                )
            },
            page = broadcasts.number,
            size = broadcasts.size,
            totalElements = broadcasts.totalElements,
            totalPages = broadcasts.totalPages,
            hasNext = broadcasts.hasNext(),
        )
    }

    companion object {
        private val DEFAULT_RANGE = Duration.ofDays(7)
        private val MAX_RANGE = Duration.ofDays(31)
    }
}

private data class NotificationCursor(val publishedAt: Instant, val id: UUID)

data class NotificationInboxPage(val items: List<NotificationInboxItem>, val nextCursor: String?, val hasNext: Boolean)
data class NotificationUnreadCount(val unreadCount: Long)
data class NotificationInboxItem(
    val id: UUID,
    val type: NotificationType,
    val title: String,
    val body: String,
    val destination: NotificationDestination,
    val read: Boolean,
    val publishedAt: Instant,
)
data class NotificationDestination(
    val type: NotificationDestinationType,
    val dailyWordId: UUID,
    val date: LocalDate,
    val version: String,
    val bookCode: String,
    val bookName: String,
    val chapter: Int,
    val verse: Int,
)
data class NotificationOperationsSummary(
    val broadcasts: Long,
    val deviceDeliveries: Long,
    val pending: Long,
    val processing: Long,
    val retryWait: Long,
    val sentToProvider: Long,
    val dead: Long,
    val opened: Long,
)
data class NotificationBroadcastOperation(
    val id: UUID,
    val type: NotificationType,
    val title: String,
    val body: String,
    val destinationType: NotificationDestinationType,
    val publishedAt: Instant,
    val expiresAt: Instant,
    val deviceDeliveries: Long,
    val sentToProvider: Long,
    val dead: Long,
    val opened: Long,
)
data class NotificationOperations(
    val from: Instant,
    val to: Instant,
    val generatedAt: Instant,
    val summary: NotificationOperationsSummary,
    val broadcasts: List<NotificationBroadcastOperation>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
    val hasNext: Boolean,
)

class NotificationNotFoundException : RuntimeException("알림을 찾을 수 없습니다.")
class NotificationInvalidCursorException : IllegalArgumentException("알림 cursor가 올바르지 않습니다.")
