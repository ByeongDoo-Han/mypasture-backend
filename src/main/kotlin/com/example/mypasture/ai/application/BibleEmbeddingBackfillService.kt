package com.example.mypasture.ai.application

import com.example.mypasture.config.AiProperties
import java.math.BigDecimal
import kotlinx.coroutines.delay
import org.springframework.stereotype.Service

/** 성경 원문이 비어 있어 embedding 작업을 시작할 수 없을 때 발생합니다. */
class BibleEmbeddingBackfillEmptySourceException : RuntimeException("Bible embedding backfill requires imported verses")

/** 다른 실행이 분산 잠금을 보유해 이번 일괄 작업을 안전하게 중단할 때 발생합니다. */
class BibleEmbeddingBackfillLockException : RuntimeException("Another Bible embedding index process owns the lock")

/** 설정된 배치 한도 안에 전체 embedding을 완료하지 못했을 때 발생합니다. */
class BibleEmbeddingBackfillIncompleteException(remainingVerses: Long) :
    RuntimeException("Bible embedding backfill stopped with $remainingVerses remaining verses")

/** 운영 로그와 비용 검증에 사용하는 전체 embedding 백필 집계 결과입니다. */
data class BibleEmbeddingBackfillSummary(
    val batches: Int,
    val processedVerses: Long,
    val indexedVerses: Long,
    val totalVerses: Long,
    val promptTokens: Long,
    val estimatedCostUsd: BigDecimal,
)

/** 작은 멱등 배치를 반복해 전체 성경 embedding을 완성하고 토큰 및 예상 비용을 합산합니다. */
@Service
class BibleEmbeddingBackfillService(
    private val indexService: BibleEmbeddingIndexService,
    private val properties: AiProperties,
) {
    suspend fun backfill(): BibleEmbeddingBackfillSummary {
        require(properties.embeddingBackfillMaxBatches > 0) { "AI_EMBEDDING_BACKFILL_MAX_BATCHES must be positive." }
        require(properties.embeddingBackfillDelayMs >= 0) { "AI_EMBEDDING_BACKFILL_DELAY_MS must not be negative." }

        var batches = 0
        var processed = 0L
        var promptTokens = 0L
        var estimatedCost = BigDecimal.ZERO

        while (batches < properties.embeddingBackfillMaxBatches) {
            val result = indexService.indexNextBatchAsync()
            if (!result.lockAcquired) throw BibleEmbeddingBackfillLockException()
            if (result.totalVerses == 0L) throw BibleEmbeddingBackfillEmptySourceException()

            if (result.processedInBatch > 0) {
                batches++
                processed += result.processedInBatch
                promptTokens += result.promptTokens
                estimatedCost = estimatedCost.add(result.estimatedCostUsd)
            }

            if (result.remainingVerses == 0L) {
                return BibleEmbeddingBackfillSummary(
                    batches = batches,
                    processedVerses = processed,
                    indexedVerses = result.indexedVerses,
                    totalVerses = result.totalVerses,
                    promptTokens = promptTokens,
                    estimatedCostUsd = estimatedCost,
                )
            }
            if (result.processedInBatch == 0) throw BibleEmbeddingBackfillIncompleteException(result.remainingVerses)
            if (properties.embeddingBackfillDelayMs > 0) delay(properties.embeddingBackfillDelayMs)
        }

        val status = indexService.status()
        throw BibleEmbeddingBackfillIncompleteException(status.remainingVerses)
    }
}
