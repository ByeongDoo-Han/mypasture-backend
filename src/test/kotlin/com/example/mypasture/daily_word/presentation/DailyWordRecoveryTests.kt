package com.example.mypasture.daily_word.presentation

import com.example.mypasture.admin_audit.infrastructure.AdminAuditLogRepository
import com.example.mypasture.admin_audit.domain.AdminAuditAction
import com.example.mypasture.ai.domain.BibleCitation
import com.example.mypasture.ai.domain.RagRetrievalStrategy
import com.example.mypasture.auth.infrastructure.UserRepository
import com.example.mypasture.bible.infrastructure.BibleBookEntityRepository
import com.example.mypasture.bible.infrastructure.BibleVerseEntityRepository
import com.example.mypasture.daily_word.application.DailyWordIncidentService
import com.example.mypasture.daily_word.application.DailyWordIncidentEmailOperationsService
import com.example.mypasture.daily_word.application.DailyWordManualOverrideException
import com.example.mypasture.daily_word.application.DailyWordService
import com.example.mypasture.daily_word.domain.DailyWordEntity
import com.example.mypasture.daily_word.domain.DailyWordGenerationJobStatus
import com.example.mypasture.daily_word.domain.DailyWordIncidentSeverity
import com.example.mypasture.daily_word.domain.DailyWordIncidentStatus
import com.example.mypasture.daily_word.domain.DailyWordIncidentType
import com.example.mypasture.daily_word.domain.DailyWordIncidentEntity
import com.example.mypasture.daily_word.domain.DailyWordIncidentEmailOutboxEntity
import com.example.mypasture.daily_word.domain.DailyWordIncidentEmailStatus
import com.example.mypasture.daily_word.domain.DailyWordSource
import com.example.mypasture.daily_word.domain.DailyWordStatus
import com.example.mypasture.daily_word.domain.GeneratedDailyWord
import com.example.mypasture.daily_word.infrastructure.DailyWordGenerationJobRepository
import com.example.mypasture.daily_word.infrastructure.DailyWordIncidentEmailOutboxRepository
import com.example.mypasture.daily_word.infrastructure.DailyWordIncidentRepository
import com.example.mypasture.daily_word.infrastructure.DailyWordRepository
import com.example.mypasture.user.domain.UserEntity
import com.example.mypasture.user.domain.UserRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import com.example.mypasture.auth.infrastructure.JwtTokenProvider
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** 오늘의 말씀 자동 장애 감지와 수동 복구의 멱등성·경쟁 조건을 검증합니다. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "app.bible.kor1910-html-path=src/test/resources/bible/kor_html",
        "app.bible.import-enabled=true",
        "app.daily-word.incident-detection-enabled=false",
        "app.daily-word.incident-email-delivery=log",
    ],
)
class DailyWordRecoveryTests(
    @Autowired private val dailyWordService: DailyWordService,
    @Autowired private val incidentService: DailyWordIncidentService,
    @Autowired private val incidentEmailService: DailyWordIncidentEmailOperationsService,
    @Autowired private val wordRepository: DailyWordRepository,
    @Autowired private val jobRepository: DailyWordGenerationJobRepository,
    @Autowired private val incidentRepository: DailyWordIncidentRepository,
    @Autowired private val outboxRepository: DailyWordIncidentEmailOutboxRepository,
    @Autowired private val auditRepository: AdminAuditLogRepository,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val bookRepository: BibleBookEntityRepository,
    @Autowired private val verseRepository: BibleVerseEntityRepository,
    @Autowired private val jwtTokenProvider: JwtTokenProvider,
    @Autowired private val mockMvc: MockMvc,
) {
    private lateinit var admin: UserEntity

    @BeforeEach
    fun cleanUp() {
        outboxRepository.deleteAllInBatch()
        incidentRepository.deleteAllInBatch()
        auditRepository.deleteAllInBatch()
        jobRepository.deleteAllInBatch()
        wordRepository.deleteAllInBatch()
        userRepository.deleteAll(
            userRepository.findAllByRoleAndDeletedAtIsNull(UserRole.ADMIN)
                .filter { it.email?.startsWith("daily-word-admin-") == true },
        )
        admin = userRepository.save(
            UserEntity(
                email = "daily-word-admin-${UUID.randomUUID()}@example.com",
                displayName = "오늘의 말씀 운영자",
                role = UserRole.ADMIN,
            ),
        )
    }

    @Test
    fun `manual draft cancels active generation and operation retry is idempotent`() {
        val date = LocalDate.now(SEOUL).plusDays(1)
        val operationId = UUID.randomUUID()
        val job = dailyWordService.queueGeneration(date, admin.id, UUID.randomUUID(), false, "수동 복구 전 생성 작업입니다")

        val first = createManual(date, operationId)
        val retried = createManual(date, operationId)

        assertEquals(first.id, retried.id)
        assertEquals(DailyWordSource.MANUAL, first.source)
        assertEquals(DailyWordGenerationJobStatus.CANCELLED, jobRepository.findById(job.id).orElseThrow().status)
        assertEquals(1, auditRepository.findAll().count { it.action == AdminAuditAction.DAILY_WORD_MANUAL_DRAFT_CREATED })
    }

    @Test
    fun `late ai result cannot overwrite a manual draft`() {
        val date = LocalDate.now(SEOUL).plusDays(2)
        val job = dailyWordService.queueGeneration(date, admin.id, UUID.randomUUID(), false, "늦은 생성 결과 보호 검증입니다")
        val token = UUID.randomUUID()
        job.claim(token, Instant.now().plusSeconds(120), Instant.now())
        jobRepository.saveAndFlush(job)
        val citation = sourceCitation()
        wordRepository.saveAndFlush(manualWord(date, citation))

        assertThrows(DailyWordManualOverrideException::class.java) {
            dailyWordService.saveGeneratedDraft(job.id, token, citation, generated())
        }
        assertEquals(DailyWordSource.MANUAL, wordRepository.findByWordDate(date)?.source)
    }

    @Test
    fun `publishing incident escalates without duplicate alerts and resolves automatically`() {
        val warningAt = Instant.parse("2026-09-01T11:30:00Z") // 20:30 Asia/Seoul
        val criticalAt = Instant.parse("2026-09-01T13:30:00Z") // 22:30 Asia/Seoul
        val targetDate = LocalDate.of(2026, 9, 2)

        incidentService.scan(warningAt)
        incidentService.scan(warningAt.plusSeconds(60))
        var incident = incidentRepository.findTop100ByOrderByLastDetectedAtDesc()
            .first { it.wordDate == targetDate && it.type == DailyWordIncidentType.PUBLISHING_MISSING }
        assertEquals(DailyWordIncidentSeverity.WARNING, incident.severity)
        assertEquals(1, outboxRepository.findAll().count { it.incident.id == incident.id })

        incidentService.acknowledge(incident.id, admin.id, UUID.randomUUID(), "경고 단계의 복구 필요 여부를 확인했습니다")
        assertEquals(DailyWordIncidentStatus.ACKNOWLEDGED, incidentRepository.findById(incident.id).orElseThrow().status)

        incidentService.scan(criticalAt)
        incident = incidentRepository.findById(incident.id).orElseThrow()
        assertEquals(DailyWordIncidentSeverity.CRITICAL, incident.severity)
        assertEquals(DailyWordIncidentStatus.OPEN, incident.status)
        assertEquals(2, incident.notificationVersion)
        assertEquals(2, outboxRepository.findAll().count { it.incident.id == incident.id })

        wordRepository.saveAndFlush(publishedWord(targetDate, sourceCitation()))
        incidentService.scan(criticalAt.plusSeconds(60))
        assertEquals(DailyWordIncidentStatus.RESOLVED, incidentRepository.findById(incident.id).orElseThrow().status)
    }

    @Test
    fun `admin email operations mask recipients filter pages and summarize all statuses`() {
        val incident = incident()
        val dead = delivery(incident, "secret.owner@example.com", DailyWordIncidentEmailStatus.DEAD)
        delivery(incident, "sent.owner@example.com", DailyWordIncidentEmailStatus.SENT)
        val token = jwtTokenProvider.issueAccessToken(admin).accessToken

        val result = mockMvc.get("/api/v1/admin/daily-words/incident-email-deliveries") {
            header("Authorization", "Bearer $token")
            param("status", "DEAD")
            param("page", "0")
            param("size", "10")
        }.andExpect {
            status { isOk() }
            jsonPath("$.summary.dead") { value(1) }
            jsonPath("$.summary.sent") { value(1) }
            jsonPath("$.totalElements") { value(1) }
            jsonPath("$.deliveries[0].id") { value(dead.id.toString()) }
            jsonPath("$.deliveries[0].maskedRecipient") { value("se***@example.com") }
            jsonPath("$.deliveries[0].errorSummary") { value("SMTP failed for [email] token=[redacted]") }
            jsonPath("$.deliveries[0].requeueAllowed") { value(true) }
        }.andReturn()

        check(!result.response.contentAsString.contains("secret.owner@example.com"))
        check(!result.response.contentAsString.contains("super-secret"))
    }

    @Test
    fun `dead email requeue is idempotent and preserves attempt history`() {
        val dead = delivery(incident(), "retry.owner@example.com", DailyWordIncidentEmailStatus.DEAD)
        val operationId = UUID.randomUUID()

        val first = incidentEmailService.requeue(dead.id, admin.id, operationId, "SMTP 설정을 수정한 뒤 다시 발송합니다")
        val retried = incidentEmailService.requeue(dead.id, admin.id, operationId, "SMTP 설정을 수정한 뒤 다시 발송합니다")

        assertEquals(first.id, retried.id)
        assertEquals(DailyWordIncidentEmailStatus.PENDING, retried.status)
        assertEquals(1, retried.attemptCount)
        assertEquals(1, auditRepository.findAll().count { it.action == AdminAuditAction.DAILY_WORD_INCIDENT_EMAIL_REQUEUED })
    }

    @Test
    fun `sent or processing email cannot be manually requeued and ordinary user is forbidden`() {
        val sent = delivery(incident(), "sent.again@example.com", DailyWordIncidentEmailStatus.SENT)
        assertThrows(IllegalStateException::class.java) {
            incidentEmailService.requeue(sent.id, admin.id, UUID.randomUUID(), "이미 성공한 메일 재처리를 거부합니다")
        }

        mockMvc.get("/api/v1/admin/daily-words/incident-email-deliveries") {
            with(user("member").roles("USER"))
        }.andExpect { status { isForbidden() } }
    }

    private fun incident() = incidentRepository.saveAndFlush(
        DailyWordIncidentEntity(
            wordDate = LocalDate.now(SEOUL),
            type = DailyWordIncidentType.PUBLISHING_MISSING,
            severity = DailyWordIncidentSeverity.CRITICAL,
            message = "오늘의 말씀 게시본이 없습니다.",
            firstDetectedAt = Instant.now(),
            lastDetectedAt = Instant.now(),
        ),
    )

    private fun delivery(
        incident: DailyWordIncidentEntity,
        recipient: String,
        targetStatus: DailyWordIncidentEmailStatus,
    ): DailyWordIncidentEmailOutboxEntity {
        val now = Instant.now()
        val delivery = DailyWordIncidentEmailOutboxEntity(
            incident = incident,
            notificationVersion = incident.notificationVersion,
            recipientUserId = admin.id,
            recipientEmail = recipient,
            nextAttemptAt = now,
            createdAt = now,
            updatedAt = now,
        )
        if (targetStatus != DailyWordIncidentEmailStatus.PENDING) {
            val token = UUID.randomUUID()
            delivery.claim(token, now.plusSeconds(60), now)
            when (targetStatus) {
                DailyWordIncidentEmailStatus.PROCESSING -> Unit
                DailyWordIncidentEmailStatus.RETRY_WAIT -> delivery.fail(token, "SMTP temporary failure", now.plusSeconds(300), now)
                DailyWordIncidentEmailStatus.SENT -> delivery.complete(token, now)
                DailyWordIncidentEmailStatus.DEAD -> delivery.fail(token, "SMTP failed for $recipient token=super-secret", null, now)
                DailyWordIncidentEmailStatus.PENDING -> Unit
            }
        }
        return outboxRepository.saveAndFlush(delivery)
    }

    private fun createManual(date: LocalDate, operationId: UUID) = dailyWordService.createManualDraft(
        date = date,
        adminId = admin.id,
        operationId = operationId,
        version = "KOR1910",
        bookCode = "GEN",
        chapter = 1,
        verse = 1,
        meditation = "하나님이 시작하신 창조의 질서를 묵상하며 오늘의 선택을 말씀 앞에서 천천히 돌아봅니다.",
        actionQuestion = "오늘 하나님의 선하심을 따라 실천할 한 가지는 무엇인가요?",
        replaceExisting = false,
        reason = "자동 생성 장애에 대비한 수동 초안입니다",
    )

    private fun sourceCitation(): BibleCitation {
        val book = bookRepository.findByVersionAndCode("KOR1910", "GEN") ?: error("GEN fixture is missing")
        val verse = verseRepository.findByVersionAndBook_IdAndChapterAndVerse("KOR1910", book.id, 1, 1)
            ?: error("GEN 1:1 fixture is missing")
        return BibleCitation(verse.id, verse.version, book.name, verse.chapter, verse.verse, verse.text)
    }

    private fun manualWord(date: LocalDate, citation: BibleCitation) = DailyWordEntity(
        wordDate = date,
        verseId = citation.verseId,
        bibleVersion = citation.version,
        bookCode = "GEN",
        bookName = citation.book,
        chapter = citation.chapter,
        verse = citation.verse,
        verseText = citation.text,
        meditation = "운영자가 실제 성경 본문을 확인하여 작성한 수동 복구 묵상 내용입니다.",
        actionQuestion = "오늘 말씀을 따라 실천할 한 가지는 무엇인가요?",
        source = DailyWordSource.MANUAL,
    )

    private fun publishedWord(date: LocalDate, citation: BibleCitation) = manualWord(date, citation).also {
        it.publish(admin.id, Instant.now())
    }

    private fun generated() = GeneratedDailyWord(
        bookName = "창세기",
        meditation = "AI가 늦게 반환한 묵상은 운영자가 만든 수동 초안을 절대로 덮어쓰지 않아야 합니다.",
        actionQuestion = "늦게 도착한 결과를 안전하게 거절할 수 있나요?",
        source = DailyWordSource.AI,
        model = "test-model",
        promptVersion = "daily-word-test-v1",
        retrievalStrategy = RagRetrievalStrategy.LEXICAL_FALLBACK,
        embeddingTokens = 1,
        promptTokens = 2,
        completionTokens = 3,
        estimatedCostUsd = BigDecimal("0.0001"),
    )

    companion object { private val SEOUL = ZoneId.of("Asia/Seoul") }
}
