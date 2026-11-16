package com.example.mypasture.daily_word.presentation

import com.example.mypasture.daily_word.application.DailyWordService
import com.example.mypasture.daily_word.domain.DailyWordEntity
import com.example.mypasture.daily_word.domain.DailyWordGenerationJobEntity
import com.example.mypasture.daily_word.domain.DailyWordGenerationJobStatus
import com.example.mypasture.daily_word.domain.DailyWordStatus
import com.example.mypasture.daily_word.infrastructure.DailyWordGenerationJobRepository
import com.example.mypasture.daily_word.infrastructure.DailyWordRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** 오늘의 말씀 운영 집계의 준비 상태, 비용, 범위 제한과 관리자 권한을 검증합니다. */
@SpringBootTest
@AutoConfigureMockMvc
class DailyWordOperationsSummaryTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val service: DailyWordService,
    @Autowired private val repository: DailyWordRepository,
    @Autowired private val jobRepository: DailyWordGenerationJobRepository,
) {
    private val today = LocalDate.now(ZoneId.of("Asia/Seoul"))

    @BeforeEach
    fun cleanUp() {
        jobRepository.deleteAllInBatch()
        repository.deleteAllInBatch()
    }

    @Test
    fun `운영 요약은 게시 준비 작업 상태와 AI 사용량을 보여준다`() {
        repository.saveAll(
            listOf(
                word(today, DailyWordStatus.PUBLISHED, 11, 22, 33, "0.0012"),
                word(today.minusDays(1), DailyWordStatus.DRAFT, 3, 4, 5, "0.0003"),
            ),
        )
        jobRepository.save(pendingJob(today.plusDays(1)))
        jobRepository.save(failedJob(today.minusDays(1)))

        val summary = service.operationsSummary(today.minusDays(1), today.plusDays(1))

        assertEquals("PUBLISHED", summary.today.status.name)
        assertEquals("MISSING", summary.tomorrow.status.name)
        assertEquals(1, summary.content.published)
        assertEquals(1, summary.content.draft)
        assertEquals(1, summary.content.missing)
        assertEquals(listOf(today.plusDays(1)), summary.content.datesNeedingAttention)
        assertEquals(1, summary.jobs.pending)
        assertEquals(1, summary.jobs.failed)
        assertEquals(14, summary.usage.embeddingTokens)
        assertEquals(26, summary.usage.promptTokens)
        assertEquals(38, summary.usage.completionTokens)
        assertEquals(0, BigDecimal("0.0015").compareTo(summary.usage.estimatedCostUsd))
    }

    @Test
    fun `운영 요약은 관리자만 조회할 수 있다`() {
        repository.save(word(today, DailyWordStatus.PUBLISHED, 1, 2, 3, "0.0001"))

        mockMvc.get("/api/v1/admin/daily-words/operations-summary") {
            with(user("admin").roles("ADMIN"))
            param("from", today.toString())
            param("to", today.plusDays(1).toString())
        }.andExpect {
            status { isOk() }
            jsonPath("$.today.status") { value("PUBLISHED") }
            jsonPath("$.tomorrow.status") { value("MISSING") }
            jsonPath("$.content.missing") { value(1) }
            jsonPath("$.usage.promptTokens") { value(2) }
        }

        mockMvc.get("/api/v1/admin/daily-words/operations-summary") {
            with(user("member").roles("USER"))
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `운영 요약은 무제한 날짜 범위를 거부한다`() {
        mockMvc.get("/api/v1/admin/daily-words/operations-summary") {
            with(user("admin").roles("ADMIN"))
            param("from", today.toString())
            param("to", today.plusDays(93).toString())
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_DAILY_WORD_REQUEST") }
        }
    }

    private fun word(
        date: LocalDate,
        status: DailyWordStatus,
        embeddingTokens: Int,
        promptTokens: Int,
        completionTokens: Int,
        cost: String,
    ) = DailyWordEntity(
        wordDate = date,
        bibleVersion = "KOR1910",
        bookCode = "GEN",
        bookName = "창세기",
        chapter = 1,
        verse = 1,
        verseText = "태초에 하나님이 천지를 창조하시니라",
        meditation = "하나님의 창조를 묵상하며 오늘의 삶을 하나님께 맡깁니다.",
        actionQuestion = "오늘 하나님께 맡길 한 가지는 무엇인가요?",
        status = status,
        published = status == DailyWordStatus.PUBLISHED,
        embeddingTokens = embeddingTokens,
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        estimatedCostUsd = BigDecimal(cost),
        publishedAt = if (status == DailyWordStatus.PUBLISHED) Instant.now() else null,
    )

    private fun pendingJob(date: LocalDate) = DailyWordGenerationJobEntity(wordDate = date)

    private fun failedJob(date: LocalDate): DailyWordGenerationJobEntity {
        val token = UUID.randomUUID()
        return DailyWordGenerationJobEntity(wordDate = date).also {
            it.claim(token, Instant.now().plusSeconds(60), Instant.now())
            it.fail(token, "테스트 생성 실패", retry = false, now = Instant.now())
            check(it.status == DailyWordGenerationJobStatus.FAILED)
        }
    }
}
