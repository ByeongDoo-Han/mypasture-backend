package com.example.mypasture.notification.application

import com.example.mypasture.auth.infrastructure.UserRepository
import com.example.mypasture.notification.domain.DevicePlatform
import com.example.mypasture.notification.infrastructure.NotificationDeviceRepository
import com.example.mypasture.user.domain.UserEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource
import java.util.UUID

/** FCM 토큰 반복 등록과 계정 변경 시 단일 활성 소유자 규칙을 검증합니다. */
@SpringBootTest
@TestPropertySource(properties = ["app.bible.kor1910-html-path=src/test/resources/bible/kor_html"])
class NotificationRegistrationTests(
    @Autowired private val userRepository: UserRepository,
    @Autowired private val notificationService: NotificationService,
    @Autowired private val deviceRepository: NotificationDeviceRepository,
) {
    @Test
    fun `same user and installation registration is idempotent`() {
        val user = newUser()
        val deviceId = "device-${UUID.randomUUID()}"
        val token = "token-${UUID.randomUUID()}"

        val first = notificationService.register(user.id, deviceId, token, DevicePlatform.ANDROID)
        val second = notificationService.register(user.id, deviceId, token, DevicePlatform.ANDROID)

        assertEquals(first.id, second.id)
        assertEquals(1, deviceRepository.findAll().count { it.fcmToken == token })
        assertTrue(second.active)
    }

    @Test
    fun `same token moves to the latest authenticated user`() {
        val previousUser = newUser()
        val currentUser = newUser()
        val deviceId = "device-${UUID.randomUUID()}"
        val token = "token-${UUID.randomUUID()}"
        val previous = notificationService.register(previousUser.id, deviceId, token, DevicePlatform.ANDROID)

        val current = notificationService.register(currentUser.id, deviceId, token, DevicePlatform.ANDROID)

        assertEquals(previous.id, current.id)
        assertEquals(currentUser.id, deviceRepository.findByFcmToken(token)?.userId)
        assertNull(deviceRepository.findByUserIdAndDeviceId(previousUser.id, deviceId))
        assertEquals(1, deviceRepository.findAll().count { it.fcmToken == token && it.active })
    }

    private fun newUser() = userRepository.save(
        UserEntity(email = "push-${UUID.randomUUID()}@example.com", displayName = "푸시 사용자"),
    )
}
