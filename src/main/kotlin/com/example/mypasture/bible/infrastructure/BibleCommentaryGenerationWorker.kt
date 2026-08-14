package com.example.mypasture.bible.infrastructure

import com.example.mypasture.bible.application.BibleCommentaryGeneratorPort
import com.example.mypasture.bible.application.BibleCommentaryService
import com.example.mypasture.bible.application.BibleReaderService
import com.example.mypasture.bible.domain.BibleCommentaryJobStatus
import com.example.mypasture.config.AiProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** DB 생성 큐에서 한 작업을 잠그고 AI 생성, 자동 검증, 초안 저장을 순차 실행합니다. */
@Component
@ConditionalOnProperty(name = ["app.ai.commentary-generation-enabled"], havingValue = "true")
class BibleCommentaryGenerationWorker(
    private val jobRepository: BibleCommentaryGenerationJobRepository,
    private val bibleReaderService: BibleReaderService,
    private val generator: BibleCommentaryGeneratorPort,
    private val commentaryService: BibleCommentaryService,
    private val properties: AiProperties,
    private val transactionTemplate: TransactionTemplate,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Scheduled(fixedDelayString = "\${app.ai.commentary-poll-delay-ms:5000}")
    fun processNext() {
        val jobId = claimNextJob() ?: return
        try {
            val job = jobRepository.findById(jobId).orElseThrow()
            val chapter = bibleReaderService.getChapter(job.version, job.bookCode, job.chapter)
            val generated = generator.generate(chapter)
            commentaryService.saveGeneratedDraft(job.id, generated, properties.commentaryPromptVersion)
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
