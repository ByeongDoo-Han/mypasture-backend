package com.example.mypasture.daily_word.presentation

import com.example.mypasture.ai.domain.BibleCitation
import com.example.mypasture.ai.domain.RagRetrievalStrategy
import com.example.mypasture.bible.infrastructure.BibleBookEntityRepository
import com.example.mypasture.bible.infrastructure.BibleVerseEntityRepository
import com.example.mypasture.daily_word.application.DailyWordGenerationException
import com.example.mypasture.daily_word.application.DailyWordService
import com.example.mypasture.daily_word.domain.DailyWordGenerationJobStatus
import com.example.mypasture.daily_word.domain.DailyWordSource
import com.example.mypasture.daily_word.domain.DailyWordStatus
import com.example.mypasture.daily_word.domain.GeneratedDailyWord
import com.example.mypasture.daily_word.infrastructure.DailyWordGenerationJobRepository
import com.example.mypasture.daily_word.infrastructure.DailyWordRepository
import com.example.mypasture.notification.infrastructure.NotificationBroadcastRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** 날짜별 생성 멱등성, 원문 검증, 관리자 게시와 공개 경계를 검증합니다. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = ["app.bible.kor1910-html-path=src/test/resources/bible/kor_html", "app.bible.import-enabled=true"])
class DailyWordPipelineTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val service: DailyWordService,
    @Autowired private val repository: DailyWordRepository,
    @Autowired private val jobRepository: DailyWordGenerationJobRepository,
    @Autowired private val notificationBroadcastRepository: NotificationBroadcastRepository,
    @Autowired private val bookRepository: BibleBookEntityRepository,
    @Autowired private val verseRepository: BibleVerseEntityRepository,
) {
    private val adminId = UUID.randomUUID()

    @BeforeEach
    fun cleanUp() {
        notificationBroadcastRepository.deleteAllInBatch()
        jobRepository.deleteAllInBatch()
        repository.deleteAllInBatch()
    }

    @Test
    fun `초안은 관리자 게시 전까지 숨기고 운영 재시도는 멱등하다`() {
        val date = LocalDate.of(2026, 9, 1)
        val operationId = UUID.randomUUID()
        val queued = service.queueGeneration(date, adminId, operationId, false, "다음 날 묵상 초안을 생성합니다")
        assertEquals(queued.id, service.queueGeneration(date, adminId, operationId, false, "다음 날 묵상 초안을 생성합니다").id)
        val draft = complete(queued.id, sourceCitation())

        mockMvc.get("/api/v1/today-word") { param("date", date.toString()) }
            .andExpect { status { isNotFound() }; jsonPath("$.code") { value("DAILY_WORD_NOT_PUBLISHED") } }

        val published = service.publish(draft.id, adminId, UUID.randomUUID(), "성경 원문과 묵상 내용을 검수했습니다")
        assertEquals(DailyWordStatus.PUBLISHED, published.status)

        mockMvc.get("/api/v1/today-word") { param("date", date.toString()) }
            .andExpect {
                status { isOk() }
                jsonPath("$.id") { value(draft.id.toString()) }
                jsonPath("$.bookCode") { value("GEN") }
                jsonPath("$.chapter") { value(1) }
                jsonPath("$.verse") { value(1) }
                jsonPath("$.publishedAt") { exists() }
            }
    }

    @Test
    fun `RAG 인용 문구는 현재 성경 절과 일치해야 한다`() {
        val job = service.queueGeneration(LocalDate.of(2026, 9, 2), adminId, UUID.randomUUID(), false, "원문 불일치 검증 작업입니다")
        val source = sourceCitation()
        assertThrows(DailyWordGenerationException::class.java) {
            complete(job.id, source.copy(text = "존재하지 않는 성경 본문"))
        }
        assertEquals(DailyWordGenerationJobStatus.PROCESSING, jobRepository.findById(job.id).orElseThrow().status)
    }

    @Test
    fun `오래된 생성 임대는 초안을 저장할 수 없다`() {
        val job = service.queueGeneration(LocalDate.of(2026, 9, 4), adminId, UUID.randomUUID(), false, "lease 위조 방지를 검증합니다")
        val activeToken = UUID.randomUUID()
        val processing = jobRepository.findById(job.id).orElseThrow()
        processing.claim(activeToken, Instant.now().plusSeconds(120), Instant.now())
        jobRepository.saveAndFlush(processing)

        assertThrows(IllegalStateException::class.java) {
            service.saveGeneratedDraft(job.id, UUID.randomUUID(), sourceCitation(), generated())
        }
        assertEquals(DailyWordGenerationJobStatus.PROCESSING, jobRepository.findById(job.id).orElseThrow().status)
    }

    @Test
    fun `비관리자는 생성 작업을 등록할 수 없다`() {
        mockMvc.post("/api/v1/admin/daily-words/2026-09-03/generation-jobs") {
            with(user("member").roles("USER"))
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = """{"operationId":"${UUID.randomUUID()}","regenerate":false,"reason":"내일 묵상 초안을 생성합니다"}"""
        }.andExpect { status { isForbidden() } }
    }

    private fun complete(jobId: UUID, citation: BibleCitation): com.example.mypasture.daily_word.domain.DailyWordEntity {
        val token = UUID.randomUUID()
        val job = jobRepository.findById(jobId).orElseThrow()
        job.claim(token, Instant.now().plusSeconds(120), Instant.now())
        jobRepository.saveAndFlush(job)
        return service.saveGeneratedDraft(jobId, token, citation, generated())
    }

    private fun sourceCitation(): BibleCitation {
        val book = bookRepository.findByVersionAndCode("KOR1910", "GEN") ?: error("GEN fixture is missing")
        val verse = verseRepository.findByVersionAndBook_IdAndChapterAndVerse("KOR1910", book.id, 1, 1)
            ?: error("GEN 1:1 fixture is missing")
        return BibleCitation(verse.id, verse.version, book.name, verse.chapter, verse.verse, verse.text)
    }

    private fun generated() = GeneratedDailyWord(
        bookName = "창세기",
        meditation = "하나님이 세상을 시작하신 말씀을 묵상하며 오늘 내 삶의 시작과 질서를 하나님께 맡깁니다.",
        actionQuestion = "오늘 하나님께 맡기며 새롭게 시작할 한 가지는 무엇인가요?",
        source = DailyWordSource.AI,
        model = "test-model",
        promptVersion = "daily-word-test-v1",
        retrievalStrategy = RagRetrievalStrategy.LEXICAL_FALLBACK,
        embeddingTokens = 7,
        promptTokens = 30,
        completionTokens = 40,
        estimatedCostUsd = BigDecimal("0.0004"),
    )
}
