package com.example.mypasture.daily_word.infrastructure

import com.example.mypasture.ai.application.BibleRetrievalPort
import com.example.mypasture.ai.domain.AiRuntimeConfigSnapshot
import com.example.mypasture.ai.domain.BibleCitation
import com.example.mypasture.ai.domain.BibleRetrievalResult
import com.example.mypasture.ai.domain.RagRetrievalStrategy
import com.example.mypasture.ai.domain.UnderstoodBibleQuestion
import com.example.mypasture.bible.infrastructure.BibleBookEntityRepository
import com.example.mypasture.bible.infrastructure.BibleVerseEntityRepository
import com.example.mypasture.daily_word.application.DailyWordGenerationPort
import com.example.mypasture.daily_word.application.DailyWordService
import com.example.mypasture.daily_word.domain.DailyWordSource
import com.example.mypasture.daily_word.domain.DailyWordStatus
import com.example.mypasture.daily_word.domain.GeneratedDailyWord
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.TestPropertySource
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** 생성 worker가 RAG와 생성 provider를 한 번씩 호출하고 lease 작업을 완료하는지 검증합니다. */
@SpringBootTest
@Import(DailyWordGenerationWorkerTests.FakeAiConfig::class)
@TestPropertySource(
    properties = [
        "app.bible.kor1910-html-path=src/test/resources/bible/kor_html",
        "app.bible.import-enabled=true",
        "app.daily-word.generation-enabled=true",
        "app.daily-word.provider=local",
        "app.daily-word.poll-delay-ms=99999999",
        "app.daily-word.generation-cron=0 0 0 1 1 *",
    ],
)
class DailyWordGenerationWorkerTests(
    @Autowired private val worker: DailyWordGenerationWorker,
    @Autowired private val service: DailyWordService,
    @Autowired private val wordRepository: DailyWordRepository,
    @Autowired private val jobRepository: DailyWordGenerationJobRepository,
    @Autowired private val bookRepository: BibleBookEntityRepository,
    @Autowired private val verseRepository: BibleVerseEntityRepository,
    @Autowired private val retriever: FakeRetriever,
    @Autowired private val generator: FakeGenerator,
) {
    @BeforeEach
    fun cleanUp() {
        jobRepository.deleteAllInBatch()
        wordRepository.deleteAllInBatch()
        retriever.calls = 0
        generator.calls = 0
    }

    @Test
    fun `worker creates one grounded draft and completes job`() = runTest {
        retriever.citation = sourceCitation()
        val date = LocalDate.of(2026, 9, 5)
        val job = service.queueGeneration(date, UUID.randomUUID(), UUID.randomUUID(), false, "오늘의 말씀 생성 worker 검증")

        worker.processNext()

        val word = wordRepository.findByWordDate(date) ?: error("daily word draft is missing")
        assertEquals(DailyWordStatus.DRAFT, word.status)
        assertEquals(retriever.citation.verseId, word.verseId)
        assertEquals(1, retriever.calls)
        assertEquals(1, generator.calls)
        assertEquals(com.example.mypasture.daily_word.domain.DailyWordGenerationJobStatus.COMPLETED, jobRepository.findById(job.id).orElseThrow().status)
    }

    private fun sourceCitation(): BibleCitation {
        val book = bookRepository.findByVersionAndCode("KOR1910", "GEN") ?: error("GEN fixture is missing")
        val verse = verseRepository.findByVersionAndBook_IdAndChapterAndVerse("KOR1910", book.id, 1, 1) ?: error("GEN 1:1 fixture is missing")
        return BibleCitation(verse.id, verse.version, book.name, verse.chapter, verse.verse, verse.text)
    }

    @TestConfiguration
    class FakeAiConfig {
        @Bean @Primary fun fakeRetriever() = FakeRetriever()
        @Bean @Primary fun fakeGenerator() = FakeGenerator()
    }

    class FakeRetriever : BibleRetrievalPort {
        lateinit var citation: BibleCitation
        var calls = 0
        override suspend fun searchRelevantVerses(question: UnderstoodBibleQuestion, limit: Int): BibleRetrievalResult {
            calls += 1
            return BibleRetrievalResult(listOf(citation), 11, BigDecimal("0.00001"), RagRetrievalStrategy.PGVECTOR)
        }
    }

    class FakeGenerator : DailyWordGenerationPort {
        var calls = 0
        override suspend fun generate(citation: BibleCitation, bookName: String, theme: String, config: AiRuntimeConfigSnapshot): GeneratedDailyWord {
            calls += 1
            return GeneratedDailyWord(
                bookName, "검색된 실제 말씀을 천천히 묵상하며 오늘의 생각과 선택을 하나님 앞에서 정돈합니다.",
                "오늘 이 말씀을 따라 바꿀 수 있는 한 가지 선택은 무엇인가요?", DailyWordSource.AI,
                "fake-model", "daily-word-test-v1", RagRetrievalStrategy.PGVECTOR, 0, 20, 30, BigDecimal("0.0002"),
            )
        }
    }
}
