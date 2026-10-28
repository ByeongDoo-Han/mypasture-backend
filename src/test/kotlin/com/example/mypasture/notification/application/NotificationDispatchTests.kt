package com.example.mypasture.notification.application

import com.example.mypasture.auth.infrastructure.UserRepository
import com.example.mypasture.daily_word.application.DailyWordService
import com.example.mypasture.daily_word.application.DailyWordPublishedPort
import com.example.mypasture.daily_word.infrastructure.DailyWordRepository
import com.example.mypasture.bible.infrastructure.BibleBookEntityRepository
import com.example.mypasture.bible.infrastructure.BibleVerseEntityRepository
import com.example.mypasture.notification.domain.DeliveryStatus
import com.example.mypasture.notification.domain.DevicePlatform
import com.example.mypasture.notification.domain.NotificationDeviceEntity
import com.example.mypasture.notification.infrastructure.NotificationDeliveryRepository
import com.example.mypasture.notification.infrastructure.NotificationDeviceRepository
import com.example.mypasture.notification.infrastructure.DailyWordNotificationSettingRepository
import com.example.mypasture.notification.infrastructure.NotificationBroadcastRepository
import com.example.mypasture.user.domain.UserEntity
import com.example.mypasture.testing.DailyWordFixtures
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyMap
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 푸시 작업의 중복 방지, 부분 실패 재시도, lease 복구와 영구 실패 정책을 검증합니다. */
@SpringBootTest
@Import(NotificationDispatchTests.MutableClockConfig::class)
@TestPropertySource(
    properties = [
        "app.bible.kor1910-html-path=src/test/resources/bible/kor_html",
        "app.bible.import-enabled=true",
        "app.firebase.enabled=false",
        "app.notification.delivery.max-attempts=3",
        "app.notification.delivery.lease-duration=30s",
        "app.notification.delivery.initial-backoff=10s",
        "app.notification.delivery.max-backoff=40s",
        "app.notification.delivery.settings-page-size=1",
        "app.notification.delivery.dispatch-batch-size=10",
        "app.notification.delivery.max-batches-per-run=10",
    ],
)
class NotificationDispatchTests(
    @Autowired private val userRepository: UserRepository,
    @Autowired private val notificationService: NotificationService,
    @Autowired private val deliveryCoordinator: NotificationDeliveryCoordinator,
    @Autowired private val deliveryRepository: NotificationDeliveryRepository,
    @Autowired private val deviceRepository: NotificationDeviceRepository,
    @Autowired private val settingRepository: DailyWordNotificationSettingRepository,
    @Autowired private val dailyWordService: DailyWordService,
    @Autowired private val dailyWordPublishedPort: DailyWordPublishedPort,
    @Autowired private val dailyWordRepository: DailyWordRepository,
    @Autowired private val broadcastRepository: NotificationBroadcastRepository,
    @Autowired private val bibleBookRepository: BibleBookEntityRepository,
    @Autowired private val bibleVerseRepository: BibleVerseEntityRepository,
    @Autowired private val clock: MutableClock,
) {
    @MockitoBean
    private lateinit var pushSender: PushSender

    @BeforeEach
    fun resetDeliveryState() {
        deliveryRepository.deleteAllInBatch()
        settingRepository.deleteAllInBatch()
        deviceRepository.deleteAllInBatch()
        broadcastRepository.deleteAllInBatch()
        dailyWordRepository.deleteAllInBatch()
        val word = DailyWordFixtures.published(LOCAL_DATE, dailyWordRepository, bibleBookRepository, bibleVerseRepository)
        dailyWordPublishedPort.record(word, requireNotNull(word.publishedAt))
        clock.reset(INITIAL_NOW)
    }

    @Test
    fun `예약 작업을 동시에 반복 실행해도 각 기기에 한 번만 발송한다`() = runTest {
        val registration = registerDueDevices(1)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val attempts = List(2) {
                executor.submit {
                    start.await()
                    runBlocking { notificationService.dispatchDueDailyWords() }
                }
            }
            start.countDown()
            attempts.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
        notificationService.dispatchDueDailyWords()

        verify(pushSender, times(1)).send(anyString(), anyString(), anyString(), anyMap())
        val pushData = mockingDetails(pushSender).invocations.single().arguments[3] as Map<*, *>
        assertEquals(broadcastRepository.findAll().single().id.toString(), pushData["notificationId"])
        val delivery = deliveriesFor(registration.user).single()
        assertEquals(delivery.id.toString(), pushData["deliveryId"])
        assertEquals(DeliveryStatus.SENT, delivery.status)
        assertEquals(1, delivery.attemptCount)
        assertNull(delivery.leaseToken)
    }

    @Test
    fun `페이지로 나눈 알림 설정에서도 발송 대상 사용자를 모두 선점한다`() = runTest {
        val registrations = List(3) { registerDueDevices(1) }

        notificationService.dispatchDueDailyWords()

        verify(pushSender, times(3)).send(anyString(), anyString(), anyString(), anyMap())
        registrations.forEach { registration ->
            assertEquals(DeliveryStatus.SENT, deliveriesFor(registration.user).single().status)
        }
    }

    @Test
    fun `게시되지 않은 날짜에는 알림을 예약하거나 발송하지 않는다`() = runTest {
        val registration = registerDueDevices(1)
        broadcastRepository.deleteAllInBatch()
        dailyWordRepository.deleteAllInBatch()

        notificationService.dispatchDueDailyWords()

        assertTrue(deliveriesFor(registration.user).isEmpty())
        verify(pushSender, times(0)).send(anyString(), anyString(), anyString(), anyMap())
    }

    @Test
    fun `일시적 실패는 지수 대기 뒤 재시도해 성공한다`() = runTest {
        val registration = registerDueDevices(1)
        val firstFailure = AtomicBoolean(true)
        doAnswer {
            if (firstFailure.compareAndSet(true, false)) throw transientFailure()
            Unit
        }.`when`(pushSender).send(anyString(), anyString(), anyString(), anyMap())

        notificationService.dispatchDueDailyWords()

        var delivery = deliveriesFor(registration.user).single()
        assertEquals(DeliveryStatus.RETRY_WAIT, delivery.status)
        assertEquals(1, delivery.attemptCount)
        assertEquals(clock.instant().plusSeconds(10), delivery.nextAttemptAt)

        clock.advance(Duration.ofSeconds(9))
        notificationService.dispatchDueDailyWords()
        verify(pushSender, times(1)).send(anyString(), anyString(), anyString(), anyMap())

        clock.advance(Duration.ofSeconds(1))
        notificationService.dispatchDueDailyWords()

        delivery = deliveriesFor(registration.user).single()
        assertEquals(DeliveryStatus.SENT, delivery.status)
        assertEquals(2, delivery.attemptCount)
        verify(pushSender, times(2)).send(anyString(), anyString(), anyString(), anyMap())
    }

    @Test
    fun `만료된 처리 임대를 복구하고 오래된 작업자는 결과를 덮지 못한다`() = runTest {
        val registration = registerDueDevices(1)
        val word = dailyWordService.getPublished(LOCAL_DATE)
        deliveryCoordinator.reserve(registration.user.id, word, registration.devices.single(), clock.instant())
        val abandonedClaim = deliveryCoordinator.claimReady(clock.instant()).single()

        var delivery = deliveriesFor(registration.user).single()
        assertEquals(DeliveryStatus.PROCESSING, delivery.status)
        assertEquals(1, delivery.attemptCount)

        clock.advance(Duration.ofSeconds(31))
        notificationService.dispatchDueDailyWords()

        delivery = deliveriesFor(registration.user).single()
        assertEquals(DeliveryStatus.SENT, delivery.status)
        assertEquals(2, delivery.attemptCount)
        assertFalse(
            deliveryCoordinator.completeFailure(
                abandonedClaim.deliveryId,
                abandonedClaim.leaseToken,
                PushFailureDisposition.TRANSIENT,
                "STALE_WORKER",
                "late result",
                clock.instant(),
            ),
        )
        assertEquals(DeliveryStatus.SENT, deliveriesFor(registration.user).single().status)
    }

    @Test
    fun `일부 기기 발송 실패는 실패한 기기만 재시도한다`() = runTest {
        val registration = registerDueDevices(2)
        val failedToken = registration.devices.last().fcmToken
        val firstFailure = AtomicBoolean(true)
        doAnswer { invocation ->
            if (invocation.arguments[0] == failedToken && firstFailure.compareAndSet(true, false)) {
                throw transientFailure()
            }
            Unit
        }.`when`(pushSender).send(anyString(), anyString(), anyString(), anyMap())

        notificationService.dispatchDueDailyWords()

        val firstResults = deliveriesFor(registration.user).associateBy { it.device.id }
        assertEquals(DeliveryStatus.SENT, firstResults.getValue(registration.devices.first().id).status)
        assertEquals(DeliveryStatus.RETRY_WAIT, firstResults.getValue(registration.devices.last().id).status)

        clock.advance(Duration.ofSeconds(10))
        notificationService.dispatchDueDailyWords()

        val sentTokens = mockingDetails(pushSender).invocations.map { it.arguments.first() as String }
        assertEquals(1, sentTokens.count { it == registration.devices.first().fcmToken })
        assertEquals(2, sentTokens.count { it == failedToken })
        assertTrue(deliveriesFor(registration.user).all { it.status == DeliveryStatus.SENT })
    }

    @Test
    fun `영구적인 토큰 오류는 재시도하지 않고 기기를 비활성화한다`() = runTest {
        val registration = registerDueDevices(1)
        doThrow(
            PushDeliveryException(
                PushFailureDisposition.PERMANENT_TOKEN,
                "UNREGISTERED",
                "Registration token is no longer valid",
            ),
        ).`when`(pushSender).send(anyString(), anyString(), anyString(), anyMap())

        notificationService.dispatchDueDailyWords()
        clock.advance(Duration.ofMinutes(5))
        notificationService.dispatchDueDailyWords()

        val delivery = deliveriesFor(registration.user).single()
        assertEquals(DeliveryStatus.DEAD, delivery.status)
        assertEquals("UNREGISTERED", delivery.errorCode)
        assertFalse(deviceRepository.findById(registration.devices.single().id).orElseThrow().active)
        verify(pushSender, times(1)).send(anyString(), anyString(), anyString(), anyMap())
    }

    @Test
    fun `기기 등록 해제는 대기 작업을 최종 실패로 옮긴 뒤 기기를 비활성화한다`() = runTest {
        val registration = registerDueDevices(1)
        val device = registration.devices.single()
        val word = dailyWordService.getPublished(LOCAL_DATE)
        deliveryCoordinator.reserve(registration.user.id, word, device, clock.instant())

        notificationService.unregister(registration.user.id, device.deviceId)
        notificationService.dispatchDueDailyWords()

        val delivery = deliveriesFor(registration.user).single()
        assertEquals(DeliveryStatus.DEAD, delivery.status)
        assertEquals("DEVICE_UNREGISTERED", delivery.errorCode)
        assertFalse(deviceRepository.findById(device.id).orElseThrow().active)
        verify(pushSender, times(0)).send(anyString(), anyString(), anyString(), anyMap())
    }

    @Test
    fun `일시적 실패도 설정한 최대 시도 횟수 뒤에는 멈춘다`() = runTest {
        val registration = registerDueDevices(1)
        doThrow(transientFailure())
            .`when`(pushSender).send(anyString(), anyString(), anyString(), anyMap())

        notificationService.dispatchDueDailyWords()
        clock.advance(Duration.ofSeconds(10))
        notificationService.dispatchDueDailyWords()
        clock.advance(Duration.ofSeconds(20))
        notificationService.dispatchDueDailyWords()

        val delivery = deliveriesFor(registration.user).single()
        assertEquals(DeliveryStatus.DEAD, delivery.status)
        assertEquals(3, delivery.attemptCount)
        verify(pushSender, times(3)).send(anyString(), anyString(), anyString(), anyMap())
    }

    private fun registerDueDevices(deviceCount: Int): Registration {
        val user = userRepository.save(
            UserEntity(email = "notification-" + UUID.randomUUID() + "@example.com", displayName = "알림 사용자"),
        )
        val devices = (1..deviceCount).map {
            notificationService.register(
                user.id,
                "device-" + UUID.randomUUID(),
                "token-" + UUID.randomUUID(),
                DevicePlatform.ANDROID,
            )
        }
        notificationService.updateSetting(user.id, true, LocalTime.of(7, 0), ZoneId.of("Asia/Seoul").id)
        return Registration(user, devices)
    }

    private fun deliveriesFor(user: UserEntity) =
        deliveryRepository.findAll().filter { it.userId == user.id }

    private fun transientFailure() =
        PushDeliveryException(PushFailureDisposition.TRANSIENT, "UNAVAILABLE", "FCM temporarily unavailable")

    private data class Registration(
        val user: UserEntity,
        val devices: List<NotificationDeviceEntity>,
    )

    @TestConfiguration
    class MutableClockConfig {
        @Bean
        @Primary
        fun notificationClock() = MutableClock(INITIAL_NOW)
    }

    class MutableClock(private var current: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = current
        fun advance(duration: Duration) {
            current = current.plus(duration)
        }
        fun reset(instant: Instant) {
            current = instant
        }
    }

    companion object {
        private val INITIAL_NOW = Instant.parse("2026-08-22T22:00:00Z")
        private val LOCAL_DATE = java.time.LocalDate.of(2026, 8, 23)
    }
}
