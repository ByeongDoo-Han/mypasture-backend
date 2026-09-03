package com.example.mypasture.bible.infrastructure

import com.example.mypasture.bible.application.BibleCommentaryEvidenceService
import com.example.mypasture.bible.application.BibleCommentaryGenerationPipeline
import com.example.mypasture.bible.application.BibleCommentaryService
import com.example.mypasture.bible.application.BibleReaderService
import com.example.mypasture.bible.domain.BibleCommentaryJobStatus
import com.example.mypasture.config.AiProperties
import com.example.mypasture.ai.application.AiRuntimeConfigService
import com.example.mypasture.ai.domain.AiUseCase
import kotlinx.coroutines.CancellationException
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** DB 생성 큐에서 한 작업을 잠그고 AI 생성, 자동 검증, 초안 저장을 순차 실행합니다. */
@Component
@ConditionalOnProperty(name = ["app.ai.commentary-generation-enabled"], havingValue = "true")
class BibleCommentaryGenerationWorker(
    private val jobRepository: BibleCommentaryGenerationJobRepository,
    private val bibleReaderService: BibleReaderService,
    private val generationPipeline: BibleCommentaryGenerationPipeline,
    private val evidenceService: BibleCommentaryEvidenceService,
    private val commentaryService: BibleCommentaryService,
    private val runtimeConfigService: AiRuntimeConfigService,
    private val properties: AiProperties,
    private val transactionTemplate: TransactionTemplate,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Scheduled(fixedDelayString = "\${app.ai.commentary-poll-delay-ms:5000}")
    suspend fun processNext() = withContext(Dispatchers.IO) {
        val jobId = claimNextJob() ?: return@withContext
        try {
            val job = jobRepository.findById(jobId).orElseThrow()
            val chapter = bibleReaderService.getChapter(job.version, job.bookCode, job.chapter)
            val evidence = evidenceService.approvedFor(job.version, job.bookCode, job.chapter)
            val config = runtimeConfigService.current(AiUseCase.BIBLE_COMMENTARY)
            val result = generationPipeline.generate(chapter, evidence, config)
            commentaryService.saveGeneratedDraft(
                job.id,
                result.commentary,
                result.evaluation,
                properties.commentaryPromptVersion,
                config.evaluationThreshold,
            )
        } catch (exception: CancellationException) {
            failJob(jobId, "서버 종료로 해설 생성이 취소되었습니다.")
            throw exception
        } catch (exception: Exception) {
            failJob(jobId, exception.message ?: exception::class.simpleName ?: "알 수 없는 생성 오류")
        }
    }

    private fun claimNextJob(): UUID? = transactionTemplate.execute {
        val job = jobRepository.lockNext(BibleCommentaryJobStatus.PENDING, PageRequest.of(0, 1)).firstOrNull()
            ?: return@execute null
        job.start(Instant.now(clock))
        job.id
    }

    private fun failJob(jobId: UUID, message: String) {
        transactionTemplate.executeWithoutResult {
            jobRepository.findById(jobId).ifPresent { it.fail(message, Instant.now(clock)) }
        }
    }
}
