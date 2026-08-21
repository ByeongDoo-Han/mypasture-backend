package com.example.mypasture.notification.application

import com.example.mypasture.daily_word.application.DailyWordService
import com.example.mypasture.daily_word.domain.DailyWordEntity
import com.example.mypasture.notification.domain.*
import com.example.mypasture.notification.infrastructure.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.*
import java.util.UUID

/** FCM 구현체를 application 계층에서 분리하는 푸시 발송 포트입니다. */
interface PushSender { suspend fun send(token: String, title: String, body: String, data: Map<String, String>) }

/** 기기 등록, 알림 설정과 시간대별 오늘의 말씀 발송을 처리합니다. */
@Service
class NotificationService(
    private val deviceRepository: NotificationDeviceRepository,
    private val settingRepository: DailyWordNotificationSettingRepository,
    private val deliveryRepository: NotificationDeliveryRepository,
    private val dailyWordService: DailyWordService,
    private val pushSender: PushSender,
    private val deliveryCoordinator: NotificationDeliveryCoordinator,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val sendSemaphore = Semaphore(MAX_CONCURRENT_PUSH_SENDS)

    @Transactional
    fun register(userId: UUID, deviceId: String, token: String, platform: DevicePlatform): NotificationDeviceEntity {
        val now = Instant.now(clock)
        val normalizedDeviceId = deviceId.trim()
        val normalizedToken = token.trim()
        val installation = deviceRepository.findByUserIdAndDeviceId(userId, normalizedDeviceId)
        val tokenOwner = deviceRepository.findByFcmToken(normalizedToken)
        val device = when {
            tokenOwner != null -> {
                if (installation != null && installation.id != tokenOwner.id) {
                    deviceRepository.delete(installation)
                    deviceRepository.flush()
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
        device.fcmToken = normalizedToken; device.active = true; device.updatedAt = now
        return deviceRepository.save(device)
    }

    @Transactional fun unregister(userId: UUID, deviceId: String) = deviceRepository.deleteByUserIdAndDeviceId(userId, deviceId)

    @Transactional(readOnly = true)
    fun setting(userId: UUID) = settingRepository.findByUserId(userId) ?: DailyWordNotificationSettingEntity(userId = userId)

    @Transactional
    fun updateSetting(userId: UUID, enabled: Boolean, time: LocalTime, timezone: String): DailyWordNotificationSettingEntity {
        ZoneId.of(timezone)
        val setting = settingRepository.findByUserId(userId) ?: DailyWordNotificationSettingEntity(userId = userId)
        setting.enabled = enabled; setting.notificationTime = time.withSecond(0).withNano(0); setting.timezone = timezone; setting.updatedAt = Instant.now(clock)
        return settingRepository.save(setting)
    }

    /** 매분 각 사용자 현지 시각을 확인하고 DB 예약을 선점한 인스턴스만 푸시를 발송합니다. */
    @Scheduled(cron = "0 * * * * *")
    suspend fun dispatchDueDailyWords() = withContext(Dispatchers.IO) {
        val now = Instant.now(clock)
        settingRepository.findAllByEnabledTrue().forEach { setting ->
            val local = ZonedDateTime.ofInstant(now, ZoneId.of(setting.timezone))
            if (local.toLocalTime().withSecond(0).withNano(0) != setting.notificationTime) return@forEach
            val word = dailyWordService.getOrCreate(local.toLocalDate())
            val devices = deviceRepository.findAllByUserIdAndActiveTrue(setting.userId)
            if (devices.isEmpty()) return@forEach
            val deliveryId = try {
                deliveryCoordinator.reserve(setting.userId, word, now)
            } catch (cause: DataIntegrityViolationException) {
                if (deliveryRepository.existsByUserIdAndDailyWordId(setting.userId, word.id)) return@forEach
                throw cause
            }
            val errors = coroutineScope {
                devices.map { device ->
                    async {
                        sendSemaphore.withPermit {
                            try {
                                pushSender.send(
                                    device.fcmToken,
                                    "오늘의 말씀",
                                    "${word.bookName} ${word.chapter}:${word.verse}",
                                    mapOf("type" to "DAILY_WORD", "date" to word.wordDate.toString()),
                                )
                                null
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: RuntimeException) {
                                error.message?.take(500)
                            }
                        }
                    }
                }.awaitAll().filterNotNull()
            }
            val error = errors.firstOrNull()
            deliveryCoordinator.complete(deliveryId, error, now)
        }
    }

    companion object {
        private const val MAX_CONCURRENT_PUSH_SENDS = 16
    }
}

/** 짧은 독립 트랜잭션으로 발송 권한을 선점하고 외부 호출 결과만 갱신합니다. */
@Service
class NotificationDeliveryCoordinator(
    private val deliveryRepository: NotificationDeliveryRepository,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun reserve(userId: UUID, dailyWord: DailyWordEntity, now: Instant): UUID {
        val delivery = NotificationDeliveryEntity(userId = userId, dailyWord = dailyWord, createdAt = now)
        return deliveryRepository.saveAndFlush(delivery).id
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun complete(deliveryId: UUID, error: String?, now: Instant) {
        val delivery = deliveryRepository.findById(deliveryId)
            .orElseThrow { IllegalStateException("Notification delivery reservation not found: $deliveryId") }
        delivery.status = if (error == null) DeliveryStatus.SENT else DeliveryStatus.FAILED
        delivery.errorMessage = error
        delivery.sentAt = if (error == null) now else null
    }
}
