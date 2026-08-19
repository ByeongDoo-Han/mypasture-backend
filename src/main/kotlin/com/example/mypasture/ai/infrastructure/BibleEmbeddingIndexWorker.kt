package com.example.mypasture.ai.infrastructure

import com.example.mypasture.ai.application.BibleEmbeddingIndexService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** 운영자가 활성화한 동안 성경 embedding을 작은 batch로 채우고 완료 후 유휴 상태를 유지합니다. */
@Component
@ConditionalOnProperty(prefix = "app.ai", name = ["embedding-indexing-enabled"], havingValue = "true")
class BibleEmbeddingIndexWorker(
    private val indexService: BibleEmbeddingIndexService,
) {
    @Scheduled(fixedDelayString = "\${app.ai.embedding-index-poll-delay-ms:10000}")
    fun indexNextBatch() {
        runCatching { indexService.indexNextBatch() }
            .onSuccess { summary ->
                if (summary.processedInBatch > 0) {
                    logger.info(
                        "Bible embedding batch complete: processed={}, indexed={}, remaining={}, promptTokens={}, estimatedCostUsd={}",
                        summary.processedInBatch,
                        summary.indexedVerses,
                        summary.remainingVerses,
                        summary.promptTokens,
                        summary.estimatedCostUsd,
                    )
                }
            }
            .onFailure { logger.error("Bible embedding batch failed", it) }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(BibleEmbeddingIndexWorker::class.java)
    }
}
