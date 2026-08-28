package com.example.mypasture.ai

import com.example.mypasture.ai.application.BibleEmbeddingBackfillEmptySourceException
import com.example.mypasture.ai.application.BibleEmbeddingBackfillIncompleteException
import com.example.mypasture.ai.application.BibleEmbeddingBackfillLockException
import com.example.mypasture.ai.application.BibleEmbeddingBackfillService
import com.example.mypasture.ai.application.BibleEmbeddingIndexService
import com.example.mypasture.ai.application.BibleEmbeddingStorePort
import com.example.mypasture.ai.application.TextEmbeddingPort
import com.example.mypasture.ai.domain.BibleEmbeddingSource
import com.example.mypasture.ai.domain.TextEmbeddingBatch
import com.example.mypasture.config.AiProperties
import java.math.BigDecimal
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** 일괄 embedding 작업이 완료, 잠금 충돌, 빈 원문, 배치 한도를 올바르게 처리하는지 검증합니다. */
class BibleEmbeddingBackfillServiceTests {
    @Test
    fun `indexes all pending verses and aggregates usage`() = runTest {
        val store = FakeStore((1L..5L).map { source(it) }.toMutableList())
        val properties = properties().copy(embeddingBatchSize = 2)
        val service = BibleEmbeddingBackfillService(
            BibleEmbeddingIndexService(properties, FakeEmbeddingPort(), store),
            properties,
        )

        val result = service.backfill()

        assertEquals(3, result.batches)
        assertEquals(5, result.processedVerses)
        assertEquals(5, result.indexedVerses)
        assertEquals(5, result.promptTokens)
        assertEquals(BigDecimal("0.000003"), result.estimatedCostUsd)
    }

    @Test
    fun `fails when another process owns the index lock`() {
        val properties = properties()
        val service = BibleEmbeddingBackfillService(
            BibleEmbeddingIndexService(properties, FakeEmbeddingPort(), FakeStore(mutableListOf(source(1)), false)),
            properties,
        )

        assertThrows(BibleEmbeddingBackfillLockException::class.java) { runTest { service.backfill() } }
    }

    @Test
    fun `fails instead of succeeding when Bible source is empty`() {
        val properties = properties()
        val service = BibleEmbeddingBackfillService(
            BibleEmbeddingIndexService(properties, FakeEmbeddingPort(), FakeStore(mutableListOf())),
            properties,
        )

        assertThrows(BibleEmbeddingBackfillEmptySourceException::class.java) { runTest { service.backfill() } }
    }

    @Test
    fun `fails when configured batch limit cannot finish backfill`() {
        val properties = properties().copy(embeddingBatchSize = 1, embeddingBackfillMaxBatches = 1)
        val service = BibleEmbeddingBackfillService(
            BibleEmbeddingIndexService(properties, FakeEmbeddingPort(), FakeStore(mutableListOf(source(1), source(2)))),
            properties,
        )

        assertThrows(BibleEmbeddingBackfillIncompleteException::class.java) { runTest { service.backfill() } }
    }

    private fun source(id: Long) = BibleEmbeddingSource(id, "시편 23:1 본문 $id", "hash-$id")

    private class FakeEmbeddingPort : TextEmbeddingPort {
        override suspend fun embed(inputs: List<String>) = TextEmbeddingBatch(
            vectors = inputs.map { FloatArray(1_536) },
            promptTokens = inputs.size,
            estimatedCostUsd = BigDecimal("0.000001"),
        )
    }

    private class FakeStore(
        private val pending: MutableList<BibleEmbeddingSource>,
        private val lockAvailable: Boolean = true,
    ) : BibleEmbeddingStorePort {
        private val indexed = mutableSetOf<Long>()

        override fun isAvailable() = true
        override fun tryAcquireIndexLock(): AutoCloseable? =
            if (lockAvailable) AutoCloseable { } else null

        override fun findPendingSources(model: String, dimensions: Int, limit: Int) = pending.take(limit)

        override fun upsert(model: String, dimensions: Int, sources: List<BibleEmbeddingSource>, vectors: List<FloatArray>) {
            indexed += sources.map { it.verseId }
            pending.removeAll(sources.toSet())
        }

        override fun countTotalVerses() = (pending.size + indexed.size).toLong()
        override fun countIndexedVerses(model: String, dimensions: Int) = indexed.size.toLong()
    }

    private fun properties() = AiProperties(
        model = "gpt-5-mini",
        embeddingModel = "text-embedding-3-small",
        embeddingDimensions = 1_536,
        embeddingBatchSize = 100,
        embeddingIndexingEnabled = false,
        embeddingIndexPollDelayMs = 10_000,
        embeddingBackfillEnabled = true,
        embeddingBackfillMaxBatches = 1_000,
        embeddingBackfillDelayMs = 0,
        embeddingInputCostPerMillion = BigDecimal("0.02"),
        apiKey = "test",
        baseUrl = "https://api.openai.com",
        maxInputChars = 300,
        maxOutputTokens = 700,
        freeDailyLimit = 10,
        timeoutMs = 12_000,
        cacheTtlSeconds = 86_400,
        ragTopK = 10,
        ragMinSimilarity = 0.35,
        ragLexicalFallbackEnabled = true,
        commentaryGenerationEnabled = false,
        commentaryPollDelayMs = 5_000,
        commentaryPromptVersion = "chapter-commentary-v1",
        commentaryMaxOutputTokens = 1_800,
        commentaryInputCostPerMillion = BigDecimal("0.25"),
        commentaryOutputCostPerMillion = BigDecimal("2.00"),
    )
}
