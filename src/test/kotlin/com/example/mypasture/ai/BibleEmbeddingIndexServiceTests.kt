package com.example.mypasture.ai

import com.example.mypasture.ai.application.BibleEmbeddingIndexService
import com.example.mypasture.ai.application.BibleEmbeddingStorePort
import com.example.mypasture.ai.application.TextEmbeddingPort
import com.example.mypasture.ai.domain.BibleEmbeddingSource
import com.example.mypasture.ai.domain.TextEmbeddingBatch
import com.example.mypasture.config.AiProperties
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** embedding index use case의 batch 멱등성, 비용 집계와 분산 lock 경계를 검증합니다. */
class BibleEmbeddingIndexServiceTests {
    @Test
    fun `indexes one pending batch and reports usage`() {
        val store = FakeEmbeddingStore(
            pending = mutableListOf(
                BibleEmbeddingSource(1, "창세기 1:1 태초에", "hash-1"),
                BibleEmbeddingSource(2, "창세기 1:2 땅이", "hash-2"),
            ),
        )
        val service = BibleEmbeddingIndexService(properties(), FakeEmbeddingPort(), store)

        val result = service.indexNextBatch()

        assertEquals(2, result.processedInBatch)
        assertEquals(2, result.indexedVerses)
        assertEquals(0, result.remainingVerses)
        assertEquals(12, result.promptTokens)
        assertEquals(BigDecimal("0.000001"), result.estimatedCostUsd)
    }

    @Test
    fun `does not call embedding provider when another instance owns lock`() {
        val store = FakeEmbeddingStore(mutableListOf(BibleEmbeddingSource(1, "본문", "hash")), lockAvailable = false)
        val provider = FakeEmbeddingPort()
        val service = BibleEmbeddingIndexService(properties(), provider, store)

        val result = service.indexNextBatch()

        assertFalse(result.lockAcquired)
        assertEquals(0, result.processedInBatch)
        assertEquals(0, provider.calls)
    }

    @Test
    fun `rejects dimensions that do not match Flyway vector schema`() {
        val service = BibleEmbeddingIndexService(
            properties().copy(embeddingDimensions = 512),
            FakeEmbeddingPort(dimensions = 512),
            FakeEmbeddingStore(mutableListOf()),
        )

        assertThrows(IllegalArgumentException::class.java) { service.status() }
    }

    private class FakeEmbeddingPort(private val dimensions: Int = 1_536) : TextEmbeddingPort {
        var calls = 0

        override fun embed(inputs: List<String>): TextEmbeddingBatch {
            calls++
            return TextEmbeddingBatch(
                vectors = inputs.map { FloatArray(dimensions) { 0.01f } },
                promptTokens = 12,
                estimatedCostUsd = BigDecimal("0.000001"),
            )
        }
    }

    private class FakeEmbeddingStore(
        private val pending: MutableList<BibleEmbeddingSource>,
        private val lockAvailable: Boolean = true,
    ) : BibleEmbeddingStorePort {
        private val indexed = mutableSetOf<Long>()

        override fun isAvailable() = true

        override fun tryAcquireIndexLock(): AutoCloseable? =
            if (lockAvailable) object : AutoCloseable { override fun close() = Unit } else null

        override fun findPendingSources(model: String, dimensions: Int, limit: Int) = pending.take(limit)

        override fun upsert(model: String, dimensions: Int, sources: List<BibleEmbeddingSource>, vectors: List<FloatArray>) {
            indexed += sources.map { it.verseId }
            pending.removeAll(sources.toSet())
        }

        override fun countTotalVerses() = (indexed.size + pending.size).toLong()

        override fun countIndexedVerses(model: String, dimensions: Int) = indexed.size.toLong()
    }

    private fun properties() =
        AiProperties(
            model = "gpt-5-mini",
            embeddingModel = "text-embedding-3-small",
            embeddingDimensions = 1_536,
            embeddingBatchSize = 100,
            embeddingIndexingEnabled = false,
            embeddingIndexPollDelayMs = 10_000,
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
