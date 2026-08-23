package com.example.mypasture.daily_word.infrastructure

import com.example.mypasture.ai.application.AiRuntimeConfigService
import com.example.mypasture.ai.application.BibleRetrievalPort
import com.example.mypasture.ai.domain.AiUseCase
import com.example.mypasture.ai.domain.BibleQuestionIntent
import com.example.mypasture.ai.domain.UnderstoodBibleQuestion
import com.example.mypasture.config.DailyWordProperties
import com.example.mypasture.daily_word.application.DailyWordGenerationPort
import com.example.mypasture.daily_word.application.DailyWordService
import com.example.mypasture.daily_word.domain.DailyWordGenerationJobStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** 날짜별 작업을 lease로 점유하고 RAG 검색 후 묵상 초안을 비동기로 생성합니다. */
@Component
@ConditionalOnProperty(name = ["app.daily-word.generation-enabled"], havingValue = "true")
class DailyWordGenerationWorker(
    private val jobRepository: DailyWordGenerationJobRepository,
    private val dailyWordService: DailyWordService,
    private val retrievalPort: BibleRetrievalPort,
    private val generator: DailyWordGenerationPort,
    private val runtimeConfigService: AiRuntimeConfigService,
    private val properties: DailyWordProperties,
    private val transactionTemplate: TransactionTemplate,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Scheduled(cron = "\${app.daily-word.generation-cron:0 0 18 * * *}", zone = "\${app.daily-word.generation-zone:Asia/Seoul}")
    fun queueTomorrow() {
        val zone = ZoneId.of(properties.generationZone)
        val targetDate = LocalDate.now(clock.withZone(zone)).plusDays(properties.targetDaysAhead)
        runCatching { dailyWordService.queueScheduled(targetDate) }
            .onFailure { logger.warn("Daily word schedule queue failed for date={}: {}", targetDate, it.message) }
    }

    @Scheduled(fixedDelayString = "\${app.daily-word.poll-delay-ms:5000}")
    suspend fun processNext() = withContext(Dispatchers.IO) {
        val claim = claimNext() ?: return@withContext
        try {
            val theme = themes[Math.floorMod(claim.wordDate.toEpochDay(), themes.size.toLong()).toInt()]
            val retrieval = retrievalPort.searchRelevantVerses(
                UnderstoodBibleQuestion(
                    originalQuestion = "오늘의 묵상 주제: $theme",
                    searchQuery = "$theme 하나님의 뜻 위로 소망 실천",
                    intent = BibleQuestionIntent.DEVOTIONAL,
                    lexicalKeywords = theme.split(' '),
                ),
                limit = 5,
            )
            val citation = retrieval.citations.firstOrNull()
                ?: error("오늘의 말씀 생성에 사용할 RAG 성경 구절을 찾지 못했습니다.")
            val config = runtimeConfigService.current(AiUseCase.DAILY_WORD)
            val providerGenerated = generator.generate(citation, citation.book, theme, config)
            val generated = providerGenerated.copy(
                retrievalStrategy = retrieval.strategy,
                embeddingTokens = retrieval.embeddingTokens,
                estimatedCostUsd = providerGenerated.estimatedCostUsd.add(retrieval.embeddingCostUsd),
            )
            dailyWordService.saveGeneratedDraft(
                claim.jobId,
                claim.leaseToken,
                citation,
                generated,
            )
        } catch (exception: CancellationException) {
            fail(claim, "서버 종료로 오늘의 말씀 생성이 취소되었습니다.")
            throw exception
        } catch (exception: Exception) {
            fail(claim, exception.message ?: exception::class.simpleName ?: "알 수 없는 생성 오류")
        }
    }

    private fun claimNext(): Claim? = transactionTemplate.execute {
        val now = Instant.now(clock)
        val job = jobRepository.findClaimable(
            DailyWordGenerationJobStatus.PENDING,
            DailyWordGenerationJobStatus.PROCESSING,
            now,
            PageRequest.of(0, 1),
        ).firstOrNull() ?: return@execute null
        if (job.attemptCount >= properties.maxAttempts) {
            val staleToken = job.leaseToken
            if (staleToken != null) job.fail(staleToken, "최대 생성 시도 횟수를 초과했습니다.", false, now)
            return@execute null
        }
        val token = UUID.randomUUID()
        job.claim(token, now.plus(properties.leaseDuration), now)
        Claim(job.id, job.wordDate, token, job.attemptCount)
    }

    private fun fail(claim: Claim, message: String) {
        transactionTemplate.executeWithoutResult {
            val job = jobRepository.findByIdForUpdate(claim.jobId) ?: return@executeWithoutResult
            job.fail(claim.leaseToken, message, claim.attemptCount < properties.maxAttempts, Instant.now(clock))
        }
    }

    private data class Claim(val jobId: UUID, val wordDate: LocalDate, val leaseToken: UUID, val attemptCount: Int)

    companion object {
        private val logger = LoggerFactory.getLogger(DailyWordGenerationWorker::class.java)
        private val themes = listOf("평안과 신뢰", "감사와 기도", "사랑과 섬김", "소망과 인내", "용서와 화해", "지혜와 순종", "새로운 시작")
    }
}
