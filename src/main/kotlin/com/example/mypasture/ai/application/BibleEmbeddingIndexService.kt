package com.example.mypasture.ai.application

import com.example.mypasture.ai.domain.BibleEmbeddingIndexSummary
import com.example.mypasture.config.AiProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service

/** pgvector가 없는 DB에서 embedding index 작업을 요청했을 때 발생합니다. */
class BibleEmbeddingIndexUnavailableException : RuntimeException("Bible embedding index requires PostgreSQL with pgvector")

/** 성경 절 embedding을 작은 멱등 batch로 생성하고 다중 서버 중복 실행을 방지합니다. */
@Service
class BibleEmbeddingIndexService(
    private val properties: AiProperties,
    private val embeddingPort: TextEmbeddingPort,
    private val storePort: BibleEmbeddingStorePort,
) {
    fun status(): BibleEmbeddingIndexSummary {
        requirePgVectorConfiguration()
        return currentStatus(lockAcquired = true)
    }

    fun indexNextBatch(): BibleEmbeddingIndexSummary = runBlocking { indexNextBatchAsync() }

    /** Scheduler에서 OpenAI embedding 응답을 기다리는 동안 worker thread를 반환합니다. */
    suspend fun indexNextBatchAsync(): BibleEmbeddingIndexSummary = withContext(Dispatchers.IO) {
        requirePgVectorConfiguration()
        val lock = storePort.tryAcquireIndexLock() ?: return@withContext currentStatus(lockAcquired = false)
        try {
            val sources = storePort.findPendingSources(
                properties.embeddingModel,
                properties.embeddingDimensions,
                properties.embeddingBatchSize,
            )
            if (sources.isEmpty()) return@withContext currentStatus(lockAcquired = true)

            val embedding = embeddingPort.embed(sources.map { it.content })
            require(embedding.vectors.size == sources.size) { "Embedding response count does not match source count." }
            require(embedding.vectors.all { it.size == properties.embeddingDimensions }) {
                "Embedding dimensions do not match bible_embeddings schema."
            }
            storePort.upsert(
                properties.embeddingModel,
                properties.embeddingDimensions,
                sources,
                embedding.vectors,
            )
            return@withContext currentStatus(
                lockAcquired = true,
                processed = sources.size,
                promptTokens = embedding.promptTokens,
                estimatedCostUsd = embedding.estimatedCostUsd,
            )
        } finally {
            lock.close()
        }
    }

    private fun currentStatus(
        lockAcquired: Boolean,
        processed: Int = 0,
        promptTokens: Int = 0,
        estimatedCostUsd: java.math.BigDecimal = java.math.BigDecimal.ZERO,
    ): BibleEmbeddingIndexSummary {
        val total = storePort.countTotalVerses()
        val indexed = storePort.countIndexedVerses(properties.embeddingModel, properties.embeddingDimensions)
        return BibleEmbeddingIndexSummary(
            totalVerses = total,
            indexedVerses = indexed,
            processedInBatch = processed,
            remainingVerses = (total - indexed).coerceAtLeast(0),
            lockAcquired = lockAcquired,
            promptTokens = promptTokens,
            estimatedCostUsd = estimatedCostUsd,
        )
    }

    private fun requirePgVectorConfiguration() {
        if (!storePort.isAvailable()) throw BibleEmbeddingIndexUnavailableException()
        require(properties.embeddingDimensions == PGVECTOR_SCHEMA_DIMENSIONS) {
            "AI_EMBEDDING_DIMENSIONS must be $PGVECTOR_SCHEMA_DIMENSIONS for the current Flyway schema."
        }
        require(properties.embeddingBatchSize in 1..MAX_BATCH_SIZE) { "AI_EMBEDDING_BATCH_SIZE must be between 1 and $MAX_BATCH_SIZE." }
    }

    companion object {
        private const val PGVECTOR_SCHEMA_DIMENSIONS = 1_536
        private const val MAX_BATCH_SIZE = 500
    }
}
