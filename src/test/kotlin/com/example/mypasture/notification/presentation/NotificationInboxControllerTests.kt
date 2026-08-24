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
@TestPropertySource(properties = ["app.bible.kor1910-html-path=src/test/resources/bible/kor_html", "app.bible.import-enabled=true"])
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
    @Autowired private val inboxService: NotificationInboxService,
    @Autowired private val deviceRepository: NotificationDeviceRepository,
    @Autowired private val deliveryRepository: NotificationDeliveryRepository,
    @Autowired private val auditRepository: AdminAuditLogRepository,
) {
    @BeforeEach
    fun ensureBibleFixture() {
        bibleImportCoordinator.importAll()
    }

    @Test
    fun `publishing daily word creates one broadcast and operation retry stays idempotent`() {
        val admin = user(UserRole.ADMIN)
        val word = word(BASE_WORD_DATE.plusDays(10))
        val operationId = UUID.randomUUID()

        dailyWordService.publish(word.id, admin.id, operationId, "원문과 묵상을 검수하고 게시합니다")
        dailyWordService.publish(word.id, admin.id, operationId, "원문과 묵상을 검수하고 게시합니다")

        assertEquals(1, broadcastRepository.findAll().count { it.dailyWord?.id == word.id })
        assertEquals(1, auditRepository.findAll().count { it.targetId == word.id })
    }

    @Test
    fun `cursor pages remain stable and read state is isolated per user`() {
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
    fun `invalid cursor is rejected and anonymous inbox access requires authentication`() {
        val member = user()
        mockMvc.get("/api/v1/notifications") { header("Authorization", "Bearer ${token(member)}"); param("cursor", "not-a-cursor") }
            .andExpect { status { isBadRequest() }; jsonPath("$.code") { value("INVALID_NOTIFICATION_REQUEST") } }
        mockMvc.get("/api/v1/notifications").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `admin operations distinguish provider acceptance from app opens and reject ordinary users`() {
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
            jsonPath("$.summary.opened") { value(1) }
            jsonPath("$.broadcasts[0].sentToProvider") { value(1) }
            jsonPath("$.broadcasts[0].opened") { value(1) }
        }
        mockMvc.get("/api/v1/admin/notifications/operations") { header("Authorization", "Bearer ${token(member)}") }
            .andExpect { status { isForbidden() } }
    }

    private fun createBroadcasts(count: Int) = (1..count).map { offset ->
        val publishedAt = Instant.now().minusSeconds((count - offset + 1L) * 60)
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

    companion object {
        private val BASE_WORD_DATE = LocalDate.of(2031, 1, 1)
    }
}
