package com.example.mypasture.notification.presentation

import com.example.mypasture.admin_audit.infrastructure.AdminAuditLogRepository
import com.example.mypasture.auth.infrastructure.JwtTokenProvider
import com.example.mypasture.auth.infrastructure.UserRepository
import com.example.mypasture.bible.infrastructure.BibleBookEntityRepository
import com.example.mypasture.bible.infrastructure.BibleImportCoordinator
import com.example.mypasture.bible.infrastructure.BibleVerseEntityRepository
import com.example.mypasture.daily_word.application.DailyWordPublishedPort
import com.example.mypasture.daily_word.application.DailyWordService
import com.example.mypasture.daily_word.domain.DailyWordEntity
import com.example.mypasture.daily_word.domain.DailyWordSource
import com.example.mypasture.daily_word.infrastructure.DailyWordRepository
import com.example.mypasture.notification.application.NotificationInboxService
import com.example.mypasture.notification.domain.DeliveryStatus
import com.example.mypasture.notification.domain.DevicePlatform
import com.example.mypasture.notification.domain.NotificationDeliveryEntity
import com.example.mypasture.notification.domain.NotificationDeviceEntity
import com.example.mypasture.notification.infrastructure.NotificationBroadcastRepository
import com.example.mypasture.notification.infrastructure.NotificationDeliveryRepository
import com.example.mypasture.notification.infrastructure.NotificationDeviceRepository
import com.example.mypasture.notification.infrastructure.NotificationReadReceiptRepository
import com.example.mypasture.notification.infrastructure.NotificationReadWatermarkRepository
import com.example.mypasture.user.domain.UserEntity
import com.example.mypasture.user.domain.UserRole
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** 인앱 알림의 게시 연동, cursor, 사용자 격리, 읽음과 관리자 집계를 실제 JWT API로 검증합니다. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:notification-inbox;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "app.bible.kor1910-html-path=src/test/resources/bible/kor_html",
        "app.bible.import-enabled=true",
    ],
)
class NotificationInboxControllerTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val jwtTokenProvider: JwtTokenProvider,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val wordRepository: DailyWordRepository,
    @Autowired private val bibleImportCoordinator: BibleImportCoordinator,
    @Autowired private val bookRepository: BibleBookEntityRepository,
    @Autowired private val verseRepository: BibleVerseEntityRepository,
    @Autowired private val dailyWordService: DailyWordService,
    @Autowired private val publishedPort: DailyWordPublishedPort,
    @Autowired private val broadcastRepository: NotificationBroadcastRepository,
    @Autowired private val receiptRepository: NotificationReadReceiptRepository,
    @Autowired private val watermarkRepository: NotificationReadWatermarkRepository,
    @Autowired private val inboxService: NotificationInboxService,
    @Autowired private val deviceRepository: NotificationDeviceRepository,
    @Autowired private val deliveryRepository: NotificationDeliveryRepository,
    @Autowired private val auditRepository: AdminAuditLogRepository,
) {
    @BeforeEach
    fun ensureBibleFixture() {
        deliveryRepository.deleteAllInBatch()
        receiptRepository.deleteAllInBatch()
        watermarkRepository.deleteAllInBatch()
        broadcastRepository.deleteAllInBatch()
        bibleImportCoordinator.importAll()
    }

    @Test
    fun `오늘의 말씀 게시는 방송 한 건을 만들고 운영 재시도는 멱등하다`() {
        val admin = user(UserRole.ADMIN)
        val word = word(BASE_WORD_DATE.plusDays(10))
        val operationId = UUID.randomUUID()

        dailyWordService.publish(word.id, admin.id, operationId, "원문과 묵상을 검수하고 게시합니다")
        dailyWordService.publish(word.id, admin.id, operationId, "원문과 묵상을 검수하고 게시합니다")

        assertEquals(1, broadcastRepository.findAll().count { it.dailyWord?.id == word.id })
        assertEquals(1, auditRepository.findAll().count { it.targetId == word.id })
    }

    @Test
    fun `커서 페이지는 안정적이고 읽음 상태는 사용자별로 격리된다`() {
        val firstUser = user()
        val secondUser = user()
        val broadcasts = createBroadcasts(3)
        val firstToken = token(firstUser)

        val firstPage = mockMvc.get("/api/v1/notifications") {
            header("Authorization", "Bearer $firstToken"); param("limit", "2")
        }.andExpect {
            status { isOk() }
            jsonPath("$.items.length()") { value(2) }
            jsonPath("$.hasNext") { value(true) }
            jsonPath("$.nextCursor") { isNotEmpty() }
        }.andReturn()
        val cursor = objectMapper.readTree(firstPage.response.contentAsString).path("nextCursor").asText()

        mockMvc.get("/api/v1/notifications") {
            header("Authorization", "Bearer $firstToken"); param("limit", "2"); param("cursor", cursor)
        }.andExpect {
            status { isOk() }
            jsonPath("$.items.length()") { value(1) }
            jsonPath("$.hasNext") { value(false) }
        }

        mockMvc.post("/api/v1/notifications/${broadcasts.last().id}/read") { header("Authorization", "Bearer $firstToken") }
            .andExpect { status { isOk() }; jsonPath("$.read") { value(true) } }
        mockMvc.get("/api/v1/notifications/unread-count") { header("Authorization", "Bearer $firstToken") }
            .andExpect { status { isOk() }; jsonPath("$.unreadCount") { value(2) } }
        mockMvc.get("/api/v1/notifications/unread-count") { header("Authorization", "Bearer ${token(secondUser)}") }
            .andExpect { status { isOk() }; jsonPath("$.unreadCount") { value(3) } }

        mockMvc.post("/api/v1/notifications/read-all") { header("Authorization", "Bearer $firstToken") }
            .andExpect { status { isOk() }; jsonPath("$.unreadCount") { value(0) } }
        mockMvc.post("/api/v1/notifications/read-all") { header("Authorization", "Bearer $firstToken") }
            .andExpect { status { isOk() }; jsonPath("$.unreadCount") { value(0) } }

        assertEquals(1, receiptRepository.findAll().count { it.userId == firstUser.id })
    }

    @Test
    fun `잘못된 커서는 거부하고 익명 알림함 접근에는 인증이 필요하다`() {
        val member = user()
        mockMvc.get("/api/v1/notifications") { header("Authorization", "Bearer ${token(member)}"); param("cursor", "not-a-cursor") }
            .andExpect { status { isBadRequest() }; jsonPath("$.code") { value("INVALID_NOTIFICATION_REQUEST") } }
        mockMvc.get("/api/v1/notifications").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `수신 확인은 사용자와 기기 소유권을 따르고 반복 요청에 멱등하다`() {
        val member = user()
        val otherMember = user()
        val broadcast = createBroadcasts(1).single()
        val device = deviceRepository.save(
            NotificationDeviceEntity(
                userId = member.id,
                deviceId = "device-${UUID.randomUUID()}",
                fcmToken = "token-${UUID.randomUUID()}-long-enough",
                platform = DevicePlatform.ANDROID,
            ),
        )
        val delivery = deliveryRepository.saveAndFlush(
            NotificationDeliveryEntity(
                userId = member.id,
                dailyWord = broadcast.dailyWord!!,
                device = device,
                status = DeliveryStatus.SENT,
                attemptCount = 1,
                nextAttemptAt = broadcast.publishedAt,
                sentAt = broadcast.publishedAt,
                createdAt = broadcast.publishedAt,
                updatedAt = broadcast.publishedAt,
            ),
        )

        acknowledge(member, delivery.id, device.deviceId, "RECEIVED_BY_APP").andExpect { status { isNoContent() } }
        var stored = deliveryRepository.findById(delivery.id).orElseThrow()
        val firstReceivedAt = requireNotNull(stored.receivedAt)
        assertEquals(null, stored.openedAt)

        acknowledge(member, delivery.id, device.deviceId, "OPENED").andExpect { status { isNoContent() } }
        acknowledge(member, delivery.id, device.deviceId, "OPENED").andExpect { status { isNoContent() } }
        stored = deliveryRepository.findById(delivery.id).orElseThrow()
        assertEquals(firstReceivedAt, stored.receivedAt)
        requireNotNull(stored.openedAt)

        acknowledge(otherMember, delivery.id, device.deviceId, "OPENED").andExpect { status { isNotFound() } }
        acknowledge(member, delivery.id, "another-device", "OPENED").andExpect { status { isNotFound() } }
    }

    @Test
    fun `관리자 운영 화면은 제공자 수락과 앱 열람을 구분하고 일반 사용자를 거부한다`() {
        val admin = user(UserRole.ADMIN)
        val member = user()
        val broadcast = createBroadcasts(1).single()
        val device = deviceRepository.save(
            NotificationDeviceEntity(
                userId = member.id,
                deviceId = "device-${UUID.randomUUID()}",
                fcmToken = "token-${UUID.randomUUID()}-long-enough",
                platform = DevicePlatform.ANDROID,
            ),
        )
        deliveryRepository.save(
            NotificationDeliveryEntity(
                userId = member.id,
                dailyWord = broadcast.dailyWord!!,
                device = device,
                status = DeliveryStatus.SENT,
                attemptCount = 1,
                nextAttemptAt = broadcast.publishedAt,
                sentAt = broadcast.publishedAt,
                createdAt = broadcast.publishedAt,
                updatedAt = broadcast.publishedAt,
            ),
        )
        inboxService.markRead(member.id, broadcast.id)
        val from = broadcast.publishedAt.minusSeconds(60)
        val to = broadcast.publishedAt.plusSeconds(60)

        mockMvc.get("/api/v1/admin/notifications/operations") {
            header("Authorization", "Bearer ${token(admin)}"); param("from", from.toString()); param("to", to.toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.summary.broadcasts") { value(1) }
            jsonPath("$.summary.sentToProvider") { value(1) }
            jsonPath("$.summary.receivedByApp") { value(0) }
            jsonPath("$.summary.openedFromPush") { value(0) }
            jsonPath("$.summary.readInApp") { value(1) }
            jsonPath("$.summary.opened") { value(1) }
            jsonPath("$.broadcasts[0].sentToProvider") { value(1) }
            jsonPath("$.broadcasts[0].receivedByApp") { value(0) }
            jsonPath("$.broadcasts[0].openedFromPush") { value(0) }
            jsonPath("$.broadcasts[0].readInApp") { value(1) }
            jsonPath("$.broadcasts[0].opened") { value(1) }
        }
        mockMvc.get("/api/v1/admin/notifications/operations") { header("Authorization", "Bearer ${token(member)}") }
            .andExpect { status { isForbidden() } }
    }

    private fun createBroadcasts(count: Int) = (1..count).map { offset ->
        val publishedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
            .minusSeconds((count - offset + 1L) * 60)
            .plusNanos(123_456_789)
        val word = word(BASE_WORD_DATE.plusDays(offset.toLong()))
        word.publish(UUID.randomUUID(), publishedAt)
        wordRepository.saveAndFlush(word)
        publishedPort.record(word, publishedAt)
        broadcastRepository.findAll().single { it.dailyWord?.id == word.id }
    }

    private fun word(date: LocalDate): DailyWordEntity {
        val book = bookRepository.findByVersionAndCode("KOR1910", "GEN") ?: error("GEN fixture is missing")
        val verse = verseRepository.findByVersionAndBook_IdAndChapterAndVerse("KOR1910", book.id, 1, 1)
            ?: error("GEN 1:1 fixture is missing")
        return wordRepository.saveAndFlush(
            DailyWordEntity(
                wordDate = date,
                verseId = verse.id,
                bibleVersion = verse.version,
                bookCode = book.code,
                bookName = book.name,
                chapter = verse.chapter,
                verse = verse.verse,
                verseText = verse.text,
                meditation = "말씀을 따라 오늘의 삶을 차분히 돌아보는 충분한 길이의 묵상입니다.",
                actionQuestion = "오늘 말씀을 따라 실천할 한 가지는 무엇인가요?",
                source = DailyWordSource.MANUAL,
            ),
        )
    }

    private fun user(role: UserRole = UserRole.USER) = userRepository.save(
        UserEntity(
            email = "notification-inbox-${UUID.randomUUID()}@example.com",
            displayName = "알림 사용자",
            role = role,
            createdAt = Instant.now().minusSeconds(86_400),
        ),
    )

    private fun token(user: UserEntity) = jwtTokenProvider.issueAccessToken(user).accessToken

    private fun acknowledge(user: UserEntity, deliveryId: UUID, deviceId: String, event: String) =
        mockMvc.post("/api/v1/notifications/deliveries/$deliveryId/ack") {
            header("Authorization", "Bearer ${token(user)}")
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("deviceId" to deviceId, "event" to event))
        }

    companion object {
        private val BASE_WORD_DATE = LocalDate.of(2031, 1, 1)
    }
}
