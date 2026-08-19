package com.example.mypasture.notification.application

import com.example.mypasture.auth.infrastructure.UserRepository
import com.example.mypasture.notification.domain.DeliveryStatus
import com.example.mypasture.notification.domain.DevicePlatform
import com.example.mypasture.notification.infrastructure.NotificationDeliveryRepository
import com.example.mypasture.user.domain.UserEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyMap
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doThrow
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
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 반복되거나 겹친 스케줄 실행이 동일한 오늘의 말씀 푸시를 다시 보내지 않는지 검증합니다. */
@SpringBootTest
@Import(NotificationDispatchTests.FixedClockConfig::class)
@TestPropertySource(properties = ["app.bible.kor1910-html-path=src/test/resources/bible/kor_html"])
class NotificationDispatchTests(
    @Autowired private val userRepository: UserRepository,
    @Autowired private val notificationService: NotificationService,
    @Autowired private val deliveryRepository: NotificationDeliveryRepository,
) {
    @MockitoBean
    private lateinit var pushSender: PushSender

    @Test
    fun `concurrent and repeated scheduler execution sends a daily word once`() {
        val user = registerDueDevice()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val attempts = List(2) {
                executor.submit {
                    start.await()
                    notificationService.dispatchDueDailyWords()
                }
            }
            start.countDown()
            attempts.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
        notificationService.dispatchDueDailyWords()

        verify(pushSender, times(1)).send(anyString(), anyString(), anyString(), anyMap())
        val delivery = deliveryRepository.findAll().single { it.userId == user.id }
        assertEquals(DeliveryStatus.SENT, delivery.status)
    }

    @Test
    fun `push failure is recorded without a sent timestamp`() {
        val user = registerDueDevice()
        doThrow(IllegalStateException("FCM unavailable"))
            .`when`(pushSender).send(anyString(), anyString(), anyString(), anyMap())

        notificationService.dispatchDueDailyWords()

        val delivery = deliveryRepository.findAll().single { it.userId == user.id }
        assertEquals(DeliveryStatus.FAILED, delivery.status)
        assertEquals("FCM unavailable", delivery.errorMessage)
        assertNull(delivery.sentAt)
    }

    private fun registerDueDevice(): UserEntity {
        val user = userRepository.save(
            UserEntity(email = "notification-${UUID.randomUUID()}@example.com", displayName = "알림 사용자"),
        )
        notificationService.register(
            user.id,
            "device-${UUID.randomUUID()}",
            "token-${UUID.randomUUID()}",
            DevicePlatform.ANDROID,
        )
        notificationService.updateSetting(user.id, true, LocalTime.of(7, 0), ZoneId.of("Asia/Seoul").id)
        return user
    }

    @TestConfiguration
    class FixedClockConfig {
        @Bean
        @Primary
        fun notificationClock(): Clock = Clock.fixed(Instant.parse("2026-08-22T22:00:00Z"), ZoneOffset.UTC)
    }
}
