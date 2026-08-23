package com.example.mypasture.notification.application

import com.example.mypasture.config.NotificationDeliveryProperties
import com.example.mypasture.daily_word.application.DailyWordService
import com.example.mypasture.daily_word.application.DailyWordNotPublishedException
import com.example.mypasture.daily_word.domain.DailyWordEntity
import com.example.mypasture.notification.domain.DailyWordNotificationSettingEntity
import com.example.mypasture.notification.domain.DeliveryStatus
import com.example.mypasture.notification.domain.DevicePlatform
import com.example.mypasture.notification.domain.NotificationDeliveryEntity
import com.example.mypasture.notification.domain.NotificationDeviceEntity
import com.example.mypasture.notification.infrastructure.DailyWordNotificationSettingRepository
import com.example.mypasture.notification.infrastructure.NotificationDeliveryRepository
import com.example.mypasture.notification.infrastructure.NotificationDeviceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/** 푸시 실패가 재시도 대상인지, 토큰 폐기 대상인지 application 계층에 전달합니다. */
enum class PushFailureDisposition { TRANSIENT, PERMANENT_TOKEN, PERMANENT }

/** 외부 푸시 공급자의 오류를 재시도 가능한 공통 오류로 변환합니다. */
class PushDeliveryException(
    val disposition: PushFailureDisposition,
    val errorCode: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** FCM 구현체를 application 계층에서 분리하는 푸시 발송 포트입니다. */
interface PushSender {
    suspend fun send(token: String, title: String, body: String, data: Map<String, String>)
}

/** 기기 등록, 알림 설정, 발송 작업 예약과 coroutine 기반 병렬 전송을 조율합니다. */
@Service
class NotificationService(
    private val deviceRepository: NotificationDeviceRepository,
    private val settingRepository: DailyWordNotificationSettingRepository,
    private val deliveryRepository: NotificationDeliveryRepository,
    private val dailyWordService: DailyWordService,
    private val pushSender: PushSender,
    private val deliveryCoordinator: NotificationDeliveryCoordinator,
    private val properties: NotificationDeliveryProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val sendSemaphore = Semaphore(MAX_CONCURRENT_PUSH_SENDS)

    @Transactional
    fun register(userId: UUID, deviceId: String, token: String, platform: DevicePlatform): NotificationDeviceEntity {
        val now = Instant.now(clock)
        val normalizedDeviceId = deviceId.trim()
        val normalizedToken = token.trim()
        val installation = deviceRepository.findByUserIdAndDeviceIdAndActiveTrue(userId, normalizedDeviceId)
        val tokenOwner = deviceRepository.findByFcmTokenAndActiveTrue(normalizedToken)
        val device = when {
            tokenOwner != null -> {
                if (installation != null && installation.id != tokenOwner.id) {
                    deactivateDevice(installation, "DEVICE_REPLACED", "Device installation was replaced.", now)
                    deviceRepository.saveAndFlush(installation)
                }
                if (tokenOwner.userId != userId) {
                    deliveryRepository.deadLetterActiveForDevice(
                        tokenOwner.id,
                        ACTIVE_DELIVERY_STATUSES,
                        DeliveryStatus.DEAD,
                        "DEVICE_REASSIGNED",
                        "Device token was reassigned to another user.",
                        now,
                    )
                }
                tokenOwner.userId = userId
                tokenOwner.deviceId = normalizedDeviceId
                tokenOwner.platform = platform
                tokenOwner
            }
            installation != null -> installation
            else -> NotificationDeviceEntity(
                userId = userId,
                deviceId = normalizedDeviceId,
                fcmToken = normalizedToken,
                platform = platform,
                updatedAt = now,
            )
        }
        device.fcmToken = normalizedToken
        device.active = true
        device.updatedAt = now
        return deviceRepository.save(device)
    }

    /** 발송 이력 FK를 보존하기 위해 기기를 삭제하지 않고 비활성화합니다. */
    @Transactional
    fun unregister(userId: UUID, deviceId: String) {
        deviceRepository.findByUserIdAndDeviceIdAndActiveTrue(userId, deviceId)?.let { device ->
            deactivateDevice(device, "DEVICE_UNREGISTERED", "Device was unregistered.", Instant.now(clock))
        }
    }

    private fun deactivateDevice(
        device: NotificationDeviceEntity,
        errorCode: String,
        errorMessage: String,
        now: Instant,
    ) {
        deliveryRepository.deadLetterActiveForDevice(
            device.id,
            ACTIVE_DELIVERY_STATUSES,
            DeliveryStatus.DEAD,
            errorCode,
            errorMessage,
            now,
        )
        device.active = false
        device.updatedAt = now
    }

    @Transactional(readOnly = true)
    fun setting(userId: UUID) =
        settingRepository.findByUserId(userId) ?: DailyWordNotificationSettingEntity(userId = userId)

    @Transactional
    fun updateSetting(
        userId: UUID,
        enabled: Boolean,
        time: LocalTime,
        timezone: String,
    ): DailyWordNotificationSettingEntity {
        ZoneId.of(timezone)
        val setting = settingRepository.findByUserId(userId) ?: DailyWordNotificationSettingEntity(userId = userId)
        setting.enabled = enabled
        setting.notificationTime = time.withSecond(0).withNano(0)
        setting.timezone = timezone
        setting.updatedAt = Instant.now(clock)
        return settingRepository.save(setting)
    }

    /**
     * 새 오늘의 말씀 발송을 예약한 뒤, 재시도 대기 및 lease 만료 작업까지 함께 처리합니다.
     * 설정은 페이지로 읽고 실제 외부 호출은 짧은 DB 트랜잭션 밖에서 수행합니다.
     */
    @Scheduled(cron = "0 * * * * *")
    suspend fun dispatchDueDailyWords() = withContext(Dispatchers.IO) {
        val now = Instant.now(clock)
        reserveDueDeliveries(now)
        repeat(properties.maxBatchesPerRun) {
            val claims = deliveryCoordinator.claimReady(now)
            if (claims.isEmpty()) return@withContext
            coroutineScope {
                claims.map { claim ->
                    async { send(claim) }
                }.awaitAll()
            }
        }
    }

    private fun reserveDueDeliveries(now: Instant) {
        val wordsByDate = mutableMapOf<java.time.LocalDate, DailyWordEntity?>()
        var pageNumber = 0
        do {
            val page = settingRepository.findAllByEnabledTrue(
                PageRequest.of(pageNumber, properties.settingsPageSize, Sort.by("id")),
            )
            page.content.forEach { setting ->
                val local = ZonedDateTime.ofInstant(now, ZoneId.of(setting.timezone))
                if (local.toLocalTime().withSecond(0).withNano(0) != setting.notificationTime) return@forEach
                val word = wordsByDate.getOrPut(local.toLocalDate()) {
                    try { dailyWordService.getPublished(local.toLocalDate()) } catch (_: DailyWordNotPublishedException) { null }
                } ?: return@forEach
                deviceRepository.findAllByUserIdAndActiveTrue(setting.userId).forEach { device ->
                    reserveDevice(setting.userId, word, device, now)
                }
            }
            pageNumber += 1
        } while (pageNumber < page.totalPages)
    }

    private fun reserveDevice(
        userId: UUID,
        dailyWord: DailyWordEntity,
        device: NotificationDeviceEntity,
        now: Instant,
    ) {
        if (deliveryRepository.existsByDailyWord_IdAndDevice_Id(dailyWord.id, device.id)) return
        try {
            deliveryCoordinator.reserve(userId, dailyWord, device, now)
        } catch (cause: DataIntegrityViolationException) {
            if (!deliveryRepository.existsByDailyWord_IdAndDevice_Id(dailyWord.id, device.id)) throw cause
        }
    }

    private suspend fun send(claim: PushDeliveryClaim) {
        sendSemaphore.withPermit {
            try {
                pushSender.send(claim.fcmToken, claim.title, claim.body, claim.data)
                deliveryCoordinator.completeSuccess(claim.deliveryId, claim.leaseToken, Instant.now(clock))
            } catch (cancelled: CancellationException) {
                // 취소 시 PROCESSING을 그대로 두어 lease 만료 후 다른 인스턴스가 복구합니다.
                throw cancelled
            } catch (error: PushDeliveryException) {
                deliveryCoordinator.completeFailure(
                    claim.deliveryId,
                    claim.leaseToken,
                    error.disposition,
                    error.errorCode,
                    error.message ?: "Push delivery failed",
                    Instant.now(clock),
                )
            } catch (error: RuntimeException) {
                deliveryCoordinator.completeFailure(
                    claim.deliveryId,
                    claim.leaseToken,
                    PushFailureDisposition.TRANSIENT,
                    error.javaClass.simpleName.uppercase(),
                    error.message ?: "Unknown push delivery error",
                    Instant.now(clock),
                )
            }
        }
    }

    companion object {
        private const val MAX_CONCURRENT_PUSH_SENDS = 16
        private val ACTIVE_DELIVERY_STATUSES = setOf(
            DeliveryStatus.PENDING,
            DeliveryStatus.PROCESSING,
            DeliveryStatus.RETRY_WAIT,
        )
    }
}

/** 외부 호출에 필요한 값만 담아 JPA entity를 트랜잭션 밖으로 노출하지 않습니다. */
data class PushDeliveryClaim(
    val deliveryId: UUID,
    val leaseToken: UUID,
    val fcmToken: String,
    val title: String,
    val body: String,
    val data: Map<String, String>,
)

/** 짧은 독립 트랜잭션으로 작업을 예약·선점하고 fencing token이 일치할 때만 결과를 반영합니다. */
@Service
class NotificationDeliveryCoordinator(
    private val deliveryRepository: NotificationDeliveryRepository,
    private val properties: NotificationDeliveryProperties,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun reserve(
        userId: UUID,
        dailyWord: DailyWordEntity,
        device: NotificationDeviceEntity,
        now: Instant,
    ): UUID {
        val delivery = NotificationDeliveryEntity(
            userId = userId,
            dailyWord = dailyWord,
            device = device,
            nextAttemptAt = now,
            createdAt = now,
        )
        return deliveryRepository.saveAndFlush(delivery).id
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun claimReady(now: Instant): List<PushDeliveryClaim> {
        val deliveries = deliveryRepository.findClaimable(
            readyStatuses = setOf(DeliveryStatus.PENDING, DeliveryStatus.RETRY_WAIT),
            processingStatus = DeliveryStatus.PROCESSING,
            now = now,
            pageable = PageRequest.of(0, properties.dispatchBatchSize),
        )
        return deliveries.map { delivery ->
            val leaseToken = UUID.randomUUID()
            delivery.status = DeliveryStatus.PROCESSING
            delivery.attemptCount += 1
            delivery.leaseToken = leaseToken
            delivery.leaseUntil = now.plus(properties.leaseDuration)
            delivery.updatedAt = now
            PushDeliveryClaim(
                deliveryId = delivery.id,
                leaseToken = leaseToken,
                fcmToken = delivery.device.fcmToken,
                title = "오늘의 말씀",
                body = delivery.dailyWord.bookName + " " + delivery.dailyWord.chapter + ":" + delivery.dailyWord.verse,
                data = mapOf(
                    "type" to "DAILY_WORD",
                    "route" to "today-word",
                    "dailyWordId" to delivery.dailyWord.id.toString(),
                    "date" to delivery.dailyWord.wordDate.toString(),
                    "version" to delivery.dailyWord.bibleVersion,
                    "bookCode" to delivery.dailyWord.bookCode,
                    "bookName" to delivery.dailyWord.bookName,
                    "chapter" to delivery.dailyWord.chapter.toString(),
                    "verse" to delivery.dailyWord.verse.toString(),
                ),
            )
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun completeSuccess(deliveryId: UUID, leaseToken: UUID, now: Instant): Boolean {
        val delivery = deliveryRepository.findLockedById(deliveryId) ?: return false
        if (!delivery.ownsLease(leaseToken)) return false
        delivery.status = DeliveryStatus.SENT
        delivery.sentAt = now
        delivery.errorCode = null
        delivery.errorMessage = null
        delivery.updatedAt = now
        delivery.clearLease()
        return true
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun completeFailure(
        deliveryId: UUID,
        leaseToken: UUID,
        disposition: PushFailureDisposition,
        errorCode: String,
        errorMessage: String,
        now: Instant,
    ): Boolean {
        val delivery = deliveryRepository.findLockedById(deliveryId) ?: return false
        if (!delivery.ownsLease(leaseToken)) return false

        delivery.errorCode = errorCode.take(MAX_ERROR_CODE_LENGTH)
        delivery.errorMessage = errorMessage.take(MAX_ERROR_MESSAGE_LENGTH)
        delivery.updatedAt = now
        delivery.clearLease()

        val exhausted = delivery.attemptCount >= properties.maxAttempts
        if (disposition != PushFailureDisposition.TRANSIENT || exhausted) {
            delivery.status = DeliveryStatus.DEAD
            if (disposition == PushFailureDisposition.PERMANENT_TOKEN) {
                delivery.device.active = false
                delivery.device.updatedAt = now
            }
        } else {
            delivery.status = DeliveryStatus.RETRY_WAIT
            delivery.nextAttemptAt = now.plus(backoffFor(delivery.attemptCount))
        }
        return true
    }

    private fun backoffFor(attemptCount: Int): Duration {
        val exponent = (attemptCount - 1).coerceIn(0, 30)
        val candidate = properties.initialBackoff.multipliedBy(1L shl exponent)
        return if (candidate > properties.maxBackoff) properties.maxBackoff else candidate
    }

    private fun NotificationDeliveryEntity.ownsLease(token: UUID) =
        status == DeliveryStatus.PROCESSING && leaseToken == token

    private fun NotificationDeliveryEntity.clearLease() {
        leaseToken = null
        leaseUntil = null
    }

    companion object {
        private const val MAX_ERROR_CODE_LENGTH = 80
        private const val MAX_ERROR_MESSAGE_LENGTH = 500
    }
}
