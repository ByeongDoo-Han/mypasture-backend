package com.example.mypasture.notification.application

import com.example.mypasture.auth.infrastructure.UserRepository
import com.example.mypasture.notification.domain.DevicePlatform
import com.example.mypasture.notification.infrastructure.NotificationDeviceRepository
import com.example.mypasture.user.domain.UserEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
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
    fun `같은 사용자와 설치의 반복 등록은 멱등하다`() {
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
    fun `같은 토큰은 마지막으로 인증한 사용자에게 옮겨진다`() {
        val previousUser = newUser()
        val currentUser = newUser()
        val deviceId = "device-${UUID.randomUUID()}"
        val token = "token-${UUID.randomUUID()}"
        val previous = notificationService.register(previousUser.id, deviceId, token, DevicePlatform.ANDROID)

        val current = notificationService.register(currentUser.id, deviceId, token, DevicePlatform.ANDROID)

        assertEquals(previous.id, current.id)
        assertEquals(currentUser.id, deviceRepository.findByFcmTokenAndActiveTrue(token)?.userId)
        assertNull(deviceRepository.findByUserIdAndDeviceIdAndActiveTrue(previousUser.id, deviceId))
        assertEquals(1, deviceRepository.findAll().count { it.fcmToken == token && it.active })
    }

    @Test
    fun `등록 해제는 비활성 이력을 남기고 재등록은 새 활성 설치를 만든다`() {
        val user = newUser()
        val deviceId = "device-${UUID.randomUUID()}"
        val token = "token-${UUID.randomUUID()}"
        val previous = notificationService.register(user.id, deviceId, token, DevicePlatform.IOS)

        notificationService.unregister(user.id, deviceId)
        val current = notificationService.register(user.id, deviceId, token, DevicePlatform.IOS)

        assertFalse(deviceRepository.findById(previous.id).orElseThrow().active)
        assertNotEquals(previous.id, current.id)
        assertTrue(current.active)
        assertEquals(1, deviceRepository.findAll().count { it.deviceId == deviceId && it.active })
    }

    @Test
    fun `활성 토큰을 다른 활성 설치로 옮겨도 과거 이력은 삭제하지 않는다`() {
        val currentUser = newUser()
        val tokenOwner = newUser()
        val deviceId = "device-${UUID.randomUUID()}"
        val replaced = notificationService.register(
            currentUser.id,
            deviceId,
            "old-token-${UUID.randomUUID()}",
            DevicePlatform.ANDROID,
        )
        val token = "token-${UUID.randomUUID()}"
        notificationService.register(tokenOwner.id, "other-${UUID.randomUUID()}", token, DevicePlatform.ANDROID)

        val current = notificationService.register(currentUser.id, deviceId, token, DevicePlatform.ANDROID)

        assertFalse(deviceRepository.findById(replaced.id).orElseThrow().active)
        assertEquals(currentUser.id, current.userId)
        assertEquals(deviceId, current.deviceId)
        assertEquals(1, deviceRepository.findAll().count { it.fcmToken == token && it.active })
    }

    private fun newUser() = userRepository.save(
        UserEntity(email = "push-${UUID.randomUUID()}@example.com", displayName = "푸시 사용자"),
    )
}
