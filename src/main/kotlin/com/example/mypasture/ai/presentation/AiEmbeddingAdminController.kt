package com.example.mypasture.ai.presentation

import com.example.mypasture.ai.application.BibleEmbeddingIndexService
import com.example.mypasture.ai.domain.BibleEmbeddingIndexSummary
import java.math.BigDecimal
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 관리자가 embedding index 진행률을 확인하고 안전한 단일 batch 생성을 요청하게 합니다. */
@RestController
@RequestMapping("/api/v1/admin/ai/embeddings")
class AiEmbeddingAdminController(
    private val indexService: BibleEmbeddingIndexService,
) : AiEmbeddingAdminApi {
    override fun status(): BibleEmbeddingIndexResponse = indexService.status().toResponse()

    override fun indexNextBatch(): BibleEmbeddingIndexResponse = indexService.indexNextBatch().toResponse()
}

/** embedding backfill 진행률, 이번 batch 비용과 분산 lock 획득 여부를 반환합니다. */
data class BibleEmbeddingIndexResponse(
    val totalVerses: Long,
    val indexedVerses: Long,
    val processedInBatch: Int,
    val remainingVerses: Long,
    val lockAcquired: Boolean,
    val promptTokens: Int,
    val estimatedCostUsd: BigDecimal,
)

private fun BibleEmbeddingIndexSummary.toResponse() =
    BibleEmbeddingIndexResponse(
        totalVerses,
        indexedVerses,
        processedInBatch,
        remainingVerses,
        lockAcquired,
        promptTokens,
        estimatedCostUsd,
    )
