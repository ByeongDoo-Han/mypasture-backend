package com.example.mypasture.ai.infrastructure

import com.example.mypasture.ai.application.BibleEmbeddingBackfillService
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/** Cloud Run Job에서 전체 성경 embedding 백필을 수행하고 사용량 및 예상 비용을 로그로 남깁니다. */
@Component
@ConditionalOnProperty(prefix = "app.ai", name = ["embedding-backfill-enabled"], havingValue = "true")
class BibleEmbeddingBackfillRunner(
    private val backfillService: BibleEmbeddingBackfillService,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) = runBlocking {
        val summary = backfillService.backfill()
        logger.info(
            "Bible embedding backfill complete: batches={}, processed={}, indexed={}, total={}, promptTokens={}, estimatedCostUsd={}",
            summary.batches,
            summary.processedVerses,
            summary.indexedVerses,
            summary.totalVerses,
            summary.promptTokens,
            summary.estimatedCostUsd,
        )
    }

    companion object {
        private val logger = LoggerFactory.getLogger(BibleEmbeddingBackfillRunner::class.java)
    }
}
