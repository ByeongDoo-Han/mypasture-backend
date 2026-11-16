package com.example.mypasture.daily_word.application

import com.example.mypasture.admin_audit.domain.AdminAuditAction
import com.example.mypasture.admin_audit.domain.AdminAuditLogEntity
import com.example.mypasture.admin_audit.infrastructure.AdminAuditLogRepository
import com.example.mypasture.ai.domain.BibleCitation
import com.example.mypasture.bible.infrastructure.BibleVerseEntityRepository
import com.example.mypasture.bible.infrastructure.BibleBookEntityRepository
import com.example.mypasture.daily_word.domain.DailyWordEntity
import com.example.mypasture.daily_word.domain.DailyWordGenerationJobEntity
import com.example.mypasture.daily_word.domain.DailyWordGenerationJobStatus
import com.example.mypasture.daily_word.domain.DailyWordStatus
import com.example.mypasture.daily_word.domain.DailyWordSource
import com.example.mypasture.daily_word.domain.GeneratedDailyWord
import com.example.mypasture.daily_word.infrastructure.DailyWordGenerationJobRepository
import com.example.mypasture.daily_word.infrastructure.DailyWordRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.math.BigDecimal
import java.util.UUID

/** 운영자가 날짜별 게시 준비 상태를 즉시 판단할 수 있는 값입니다. */
enum class DailyWordReadinessStatus { MISSING, DRAFT, PUBLISHED, REJECTED }

/** 오늘 또는 내일의 게시 준비 상태입니다. */
data class DailyWordReadiness(
    val date: LocalDate,
    val status: DailyWordReadinessStatus,
    val publishedAt: Instant?,
)

/** 제한된 날짜 범위의 콘텐츠 상태 집계입니다. */
data class DailyWordContentSummary(
    val draft: Int,
    val published: Int,
    val rejected: Int,
    val missing: Int,
    val datesNeedingAttention: List<LocalDate>,
)

/** 생성 작업 전체를 로딩하지 않고 DB 집계로 계산한 상태 요약입니다. */
data class DailyWordJobSummary(
    val pending: Long,
    val processing: Long,
    val completed: Long,
    val failed: Long,
    val cancelled: Long,
    val oldestActiveCreatedAt: Instant?,
)

/** 날짜 범위에 저장된 현재 콘텐츠의 AI 사용량과 예상 비용 합계입니다. */
data class DailyWordUsageSummary(
    val embeddingTokens: Long,
    val promptTokens: Long,
    val completionTokens: Long,
    val estimatedCostUsd: BigDecimal,
)

/** 오늘의 말씀 운영 화면에서 한 번에 소비하는 읽기 전용 집계입니다. */
data class DailyWordOperationsSummary(
    val from: LocalDate,
    val to: LocalDate,
    val generatedAt: Instant,
    val today: DailyWordReadiness,
    val tomorrow: DailyWordReadiness,
    val content: DailyWordContentSummary,
    val jobs: DailyWordJobSummary,
    val usage: DailyWordUsageSummary,
)

/** 오늘의 말씀 생성 큐, 원문 재검증, 관리자 검수와 게시 규칙을 조합합니다. */
@Service
@Transactional(readOnly = true)
class DailyWordService(
    private val repository: DailyWordRepository,
    private val jobRepository: DailyWordGenerationJobRepository,
    private val bookRepository: BibleBookEntityRepository,
    private val verseRepository: BibleVerseEntityRepository,
    private val auditRepository: AdminAuditLogRepository,
    private val publishedPort: DailyWordPublishedPort,
    private val rotation: DailyWordRotationService,
    private val passageReader: DailyWordPassageReader,
    private val properties: com.example.mypasture.config.DailyWordProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun getPublished(date: LocalDate? = null): DailyWordEntity {
        val today = LocalDate.now(clock.withZone(SEOUL))
        val target = date ?: today
        rotation.ensurePublished(target, today)
        return repository.findByWordDateAndStatus(target, DailyWordStatus.PUBLISHED)
            ?.also { verifyStoredVerse(it) }
            ?: throw DailyWordNotPublishedException(target)
    }

    fun list(from: LocalDate, to: LocalDate, status: DailyWordStatus?): List<DailyWordEntity> {
        validateRange(from, to)
        return repository.findForAdmin(from, to, status)
    }

    fun listJobs(): List<DailyWordGenerationJobEntity> = jobRepository.findAllByOrderByCreatedAtDesc().take(100)

    /** 콘텐츠는 최대 93개만 읽고 생성 작업 수는 DB에서 집계해 운영 조회 비용을 제한합니다. */
    fun operationsSummary(from: LocalDate, to: LocalDate): DailyWordOperationsSummary {
        validateRange(from, to)
        val words = repository.findForAdmin(from, to, null)
        val wordsByDate = words.associateBy { it.wordDate }
        val dates = (0..ChronoUnit.DAYS.between(from, to)).map { from.plusDays(it) }
        val activeStatuses = listOf(DailyWordGenerationJobStatus.PENDING, DailyWordGenerationJobStatus.PROCESSING)
        val today = LocalDate.now(clock.withZone(SEOUL))

        return DailyWordOperationsSummary(
            from = from,
            to = to,
            generatedAt = Instant.now(clock),
            today = readiness(today),
            tomorrow = readiness(today.plusDays(1)),
            content = DailyWordContentSummary(
                draft = words.count { it.status == DailyWordStatus.DRAFT },
                published = words.count { it.status == DailyWordStatus.PUBLISHED },
                rejected = words.count { it.status == DailyWordStatus.REJECTED },
                missing = dates.count { it !in wordsByDate },
                datesNeedingAttention = dates.filter {
                    !it.isBefore(today) && wordsByDate[it]?.status != DailyWordStatus.PUBLISHED
                },
            ),
            jobs = DailyWordJobSummary(
                pending = countJobs(from, to, DailyWordGenerationJobStatus.PENDING),
                processing = countJobs(from, to, DailyWordGenerationJobStatus.PROCESSING),
                completed = countJobs(from, to, DailyWordGenerationJobStatus.COMPLETED),
                failed = countJobs(from, to, DailyWordGenerationJobStatus.FAILED),
                cancelled = countJobs(from, to, DailyWordGenerationJobStatus.CANCELLED),
                oldestActiveCreatedAt = jobRepository
                    .findFirstByWordDateBetweenAndStatusInOrderByCreatedAtAsc(from, to, activeStatuses)
                    ?.createdAt,
            ),
            usage = DailyWordUsageSummary(
                embeddingTokens = words.sumOf { it.embeddingTokens.toLong() },
                promptTokens = words.sumOf { it.promptTokens.toLong() },
                completionTokens = words.sumOf { it.completionTokens.toLong() },
                estimatedCostUsd = words.fold(BigDecimal.ZERO) { total, word -> total + word.estimatedCostUsd },
            ),
        )
    }

    /** 스케줄러는 이미 콘텐츠나 활성 작업이 있으면 비용을 발생시키지 않고 건너뜁니다. */
    @Transactional
    fun queueScheduled(date: LocalDate): DailyWordGenerationJobEntity? {
        if (properties.rotationEnabled) return null
        if (repository.findByWordDate(date) != null || hasActiveJob(date)) return null
        return saveJob(DailyWordGenerationJobEntity(wordDate = date, requestReason = "scheduled"))
    }

    /** 관리자 생성 요청을 operationId로 멱등 처리하고 재생성 여부를 명시적으로 검사합니다. */
    @Transactional
    fun queueGeneration(
        date: LocalDate,
        adminId: UUID,
        operationId: UUID,
        regenerate: Boolean,
        reason: String,
    ): DailyWordGenerationJobEntity {
        if (properties.rotationEnabled) throw DailyWordConflictException("요일별 본문 순환 제공 중에는 AI 생성 작업을 예약하지 않습니다. 수동 복구 초안을 사용해 주세요.")
        val normalizedReason = normalizeReason(reason)
        val fingerprint = fingerprint("QUEUE", "$date|$regenerate|$normalizedReason")
        existingJob(operationId, AdminAuditAction.DAILY_WORD_GENERATION_QUEUED, fingerprint)?.let { return it }
        if (hasActiveJob(date)) throw DailyWordConflictException("해당 날짜의 생성 작업이 이미 진행 중입니다.")
        val current = repository.findByWordDate(date)
        if (current != null && !regenerate) throw DailyWordConflictException("이미 콘텐츠가 있습니다. 재생성 여부를 확인해 주세요.")
        if (current?.status == DailyWordStatus.PUBLISHED) throw DailyWordConflictException("게시된 콘텐츠는 재생성할 수 없습니다.")
        if (current?.source == DailyWordSource.MANUAL) throw DailyWordConflictException("수동 복구 초안은 AI로 재생성할 수 없습니다.")

        val job = saveJob(DailyWordGenerationJobEntity(wordDate = date, requestedBy = adminId, requestReason = normalizedReason))
        audit(operationId, adminId, AdminAuditAction.DAILY_WORD_GENERATION_QUEUED, job.id, normalizedReason, fingerprint, "date=$date;regenerate=$regenerate")
        return job
    }

    /** lease를 보유한 워커만 RAG 결과를 실제 DB 절과 비교한 뒤 초안을 저장할 수 있습니다. */
    @Transactional
    fun saveGeneratedDraft(jobId: UUID, leaseToken: UUID, citation: BibleCitation, generated: GeneratedDailyWord): DailyWordEntity {
        rotation.lockIfEnabled()
        val job = jobRepository.findByIdForUpdate(jobId) ?: throw DailyWordNotFoundException("생성 작업을 찾을 수 없습니다.")
        check(job.status == DailyWordGenerationJobStatus.PROCESSING && job.leaseToken == leaseToken) {
            "오늘의 말씀 생성 작업 lease가 유효하지 않습니다."
        }
        validateGenerated(generated)
        val source = verifiedVerse(citation)
        val now = Instant.now(clock)
        val word = repository.findByWordDateForUpdate(job.wordDate)?.also {
            check(it.status != DailyWordStatus.PUBLISHED) { "게시된 오늘의 말씀은 생성 작업으로 덮어쓸 수 없습니다." }
            if (it.source == DailyWordSource.MANUAL) throw DailyWordManualOverrideException()
            it.replaceDraft(citation, source.book.code, generated.copy(bookName = source.book.name), now)
        } ?: DailyWordEntity(
            wordDate = job.wordDate,
            bibleVersion = citation.version,
            bookCode = source.book.code,
            bookName = source.book.name,
            chapter = citation.chapter,
            verse = citation.verse,
            verseText = citation.text,
            meditation = generated.meditation.trim(),
            actionQuestion = generated.actionQuestion.trim(),
            verseId = citation.verseId,
            status = DailyWordStatus.DRAFT,
            published = false,
            source = generated.source,
            contentRevision = 1,
            model = generated.model,
            promptVersion = generated.promptVersion,
            retrievalStrategy = generated.retrievalStrategy,
            embeddingTokens = generated.embeddingTokens,
            promptTokens = generated.promptTokens,
            completionTokens = generated.completionTokens,
            estimatedCostUsd = generated.estimatedCostUsd,
            generatedAt = now,
            createdAt = now,
            updatedAt = now,
        )
        val saved = repository.save(word)
        job.complete(saved.id, leaseToken, now)
        return saved
    }

    /** 실제 DB 성경 절을 선택해 AI 장애 시에도 게시 가능한 관리자 초안을 만듭니다. */
    @Transactional
    fun createManualDraft(
        date: LocalDate,
        adminId: UUID,
        operationId: UUID,
        version: String,
        bookCode: String,
        chapter: Int,
        verse: Int,
        meditation: String,
        actionQuestion: String,
        replaceExisting: Boolean,
        reason: String,
    ): DailyWordEntity {
        rotation.lockIfEnabled()
        val today = LocalDate.now(clock.withZone(SEOUL))
        require(!date.isBefore(today) && !date.isAfter(today.plusDays(14))) { "수동 초안은 오늘부터 14일 이내 날짜만 만들 수 있습니다." }
        validateCopy(meditation, actionQuestion)
        val normalizedReason = normalizeReason(reason)
        val normalizedVersion = version.trim().uppercase()
        val normalizedBookCode = bookCode.trim().uppercase()
        val fingerprint = fingerprint(
            "MANUAL",
            "$date|$normalizedVersion|$normalizedBookCode|$chapter|$verse|${meditation.trim()}|${actionQuestion.trim()}|$replaceExisting|$normalizedReason",
        )
        existingWord(operationId, AdminAuditAction.DAILY_WORD_MANUAL_DRAFT_CREATED, fingerprint)?.let { return it }

        val now = Instant.now(clock)
        jobRepository.findActiveForUpdate(date, ACTIVE_JOB_STATUSES).forEach {
            it.cancel("수동 초안 생성으로 취소되었습니다.", now)
        }
        val book = bookRepository.findByVersionAndCode(normalizedVersion, normalizedBookCode)
            ?: throw DailyWordNotFoundException("성경 권을 찾을 수 없습니다.")
        val source = verseRepository.findByVersionAndBook_IdAndChapterAndVerse(normalizedVersion, book.id, chapter, verse)
            ?: throw DailyWordNotFoundException("성경 절을 찾을 수 없습니다.")
        val citation = source.toCitation()
        val current = repository.findByWordDateForUpdate(date)
        if (current != null && !replaceExisting) throw DailyWordConflictException("이미 콘텐츠가 있습니다. 기존 초안 교체 여부를 확인해 주세요.")
        if (current?.status == DailyWordStatus.PUBLISHED) throw DailyWordConflictException("게시된 콘텐츠는 수동 초안으로 교체할 수 없습니다.")

        val word = current?.also {
            it.replaceManualDraft(citation, book.code, book.name, meditation, actionQuestion, now)
        } ?: DailyWordEntity(
            wordDate = date,
            verseId = source.id,
            bibleVersion = source.version,
            bookCode = book.code,
            bookName = book.name,
            chapter = source.chapter,
            verse = source.verse,
            verseText = source.text,
            meditation = meditation.trim(),
            actionQuestion = actionQuestion.trim(),
            status = DailyWordStatus.DRAFT,
            source = DailyWordSource.MANUAL,
            generatedAt = now,
            createdAt = now,
            updatedAt = now,
        )
        val saved = repository.save(word)
        audit(
            operationId,
            adminId,
            AdminAuditAction.DAILY_WORD_MANUAL_DRAFT_CREATED,
            saved.id,
            normalizedReason,
            fingerprint,
            "date=$date;verseId=${source.id};revision=${saved.contentRevision};replace=$replaceExisting",
        )
        return saved
    }

    @Transactional
    fun updateDraft(
        id: UUID,
        adminId: UUID,
        operationId: UUID,
        verseId: Long,
        meditation: String,
        actionQuestion: String,
        reason: String,
    ): DailyWordEntity {
        rotation.lockIfEnabled()
        val normalizedReason = normalizeReason(reason)
        validateCopy(meditation, actionQuestion)
        val fingerprint = fingerprint("UPDATE", "$id|$verseId|${meditation.trim()}|${actionQuestion.trim()}|$normalizedReason")
        existingWord(operationId, AdminAuditAction.DAILY_WORD_UPDATED, fingerprint)?.let { return it }
        val word = repository.findByIdForUpdate(id) ?: throw DailyWordNotFoundException("오늘의 말씀을 찾을 수 없습니다.")
        val source = verseRepository.findById(verseId).orElseThrow { DailyWordNotFoundException("성경 절을 찾을 수 없습니다.") }
        val citation = source.toCitation()
        word.edit(citation, source.book.code, source.book.name, meditation, actionQuestion, Instant.now(clock))
        audit(operationId, adminId, AdminAuditAction.DAILY_WORD_UPDATED, word.id, normalizedReason, fingerprint, "date=${word.wordDate};revision=${word.contentRevision}")
        return word
    }

    @Transactional
    fun publish(id: UUID, adminId: UUID, operationId: UUID, reason: String): DailyWordEntity {
        rotation.lockIfEnabled()
        val normalizedReason = normalizeReason(reason)
        val fingerprint = fingerprint("PUBLISH", "$id|$normalizedReason")
        existingWord(operationId, AdminAuditAction.DAILY_WORD_PUBLISHED, fingerprint)?.let { return it }
        val word = repository.findByIdForUpdate(id) ?: throw DailyWordNotFoundException("오늘의 말씀을 찾을 수 없습니다.")
        verifyStoredVerse(word)
        val publishedAt = Instant.now(clock)
        word.publish(adminId, publishedAt)
        publishedPort.record(word, publishedAt)
        audit(operationId, adminId, AdminAuditAction.DAILY_WORD_PUBLISHED, word.id, normalizedReason, fingerprint, "date=${word.wordDate};revision=${word.contentRevision}")
        return word
    }

    @Transactional
    fun reject(id: UUID, adminId: UUID, operationId: UUID, reason: String): DailyWordEntity {
        rotation.lockIfEnabled()
        val normalizedReason = normalizeReason(reason)
        val fingerprint = fingerprint("REJECT", "$id|$normalizedReason")
        existingWord(operationId, AdminAuditAction.DAILY_WORD_REJECTED, fingerprint)?.let { return it }
        val word = repository.findByIdForUpdate(id) ?: throw DailyWordNotFoundException("오늘의 말씀을 찾을 수 없습니다.")
        word.reject(adminId, normalizedReason, Instant.now(clock))
        audit(operationId, adminId, AdminAuditAction.DAILY_WORD_REJECTED, word.id, normalizedReason, fingerprint, "date=${word.wordDate};revision=${word.contentRevision}")
        return word
    }

    private fun saveJob(job: DailyWordGenerationJobEntity): DailyWordGenerationJobEntity =
        try {
            jobRepository.saveAndFlush(job)
        } catch (_: DataIntegrityViolationException) {
            throw DailyWordConflictException("해당 날짜의 생성 작업이 이미 진행 중입니다.")
        }

    private fun hasActiveJob(date: LocalDate) = jobRepository.existsByWordDateAndStatusIn(
        date,
        listOf(DailyWordGenerationJobStatus.PENDING, DailyWordGenerationJobStatus.PROCESSING),
    )

    private fun countJobs(from: LocalDate, to: LocalDate, status: DailyWordGenerationJobStatus) =
        jobRepository.countByWordDateBetweenAndStatus(from, to, status)

    private fun readiness(date: LocalDate): DailyWordReadiness {
        val word = repository.findByWordDate(date)
        val status = when (word?.status) {
            DailyWordStatus.DRAFT -> DailyWordReadinessStatus.DRAFT
            DailyWordStatus.PUBLISHED -> DailyWordReadinessStatus.PUBLISHED
            DailyWordStatus.REJECTED -> DailyWordReadinessStatus.REJECTED
            null -> DailyWordReadinessStatus.MISSING
        }
        return DailyWordReadiness(date, status, word?.publishedAt)
    }

    private fun validateRange(from: LocalDate, to: LocalDate) {
        require(!to.isBefore(from)) { "조회 종료일은 시작일보다 빠를 수 없습니다." }
        require(!to.isAfter(from.plusDays(92))) { "조회 기간은 최대 93일입니다." }
    }

    private fun verifiedVerse(citation: BibleCitation) =
        verseRepository.findById(citation.verseId).orElseThrow { DailyWordGenerationException("RAG 절을 DB에서 찾을 수 없습니다.") }
            .also {
                if (it.version != citation.version || it.chapter != citation.chapter || it.verse != citation.verse || it.text != citation.text) {
                    throw DailyWordGenerationException("RAG 절이 현재 성경 원문과 일치하지 않습니다.")
                }
            }

    private fun verifyStoredVerse(word: DailyWordEntity) {
        passageReader.verify(word)
    }

    private fun validateGenerated(generated: GeneratedDailyWord) = validateCopy(generated.meditation, generated.actionQuestion)

    private fun validateCopy(meditation: String, actionQuestion: String) {
        require(meditation.trim().length in 30..1_000) { "묵상은 30자에서 1000자 사이여야 합니다." }
        require(actionQuestion.trim().length in 10..300) { "실천 질문은 10자에서 300자 사이여야 합니다." }
    }

    private fun normalizeReason(reason: String) = reason.trim().also {
        require(it.length in 5..500) { "관리자 조치 사유는 5자에서 500자 사이여야 합니다." }
    }

    private fun existingJob(operationId: UUID, action: AdminAuditAction, requestFingerprint: String): DailyWordGenerationJobEntity? =
        existingAudit(operationId, action, requestFingerprint)?.let {
            jobRepository.findById(it.targetId).orElseThrow { DailyWordNotFoundException("기존 생성 작업을 찾을 수 없습니다.") }
        }

    private fun existingWord(operationId: UUID, action: AdminAuditAction, requestFingerprint: String): DailyWordEntity? =
        existingAudit(operationId, action, requestFingerprint)?.let {
            repository.findById(it.targetId).orElseThrow { DailyWordNotFoundException("기존 오늘의 말씀을 찾을 수 없습니다.") }
        }

    private fun existingAudit(operationId: UUID, action: AdminAuditAction, requestFingerprint: String) =
        auditRepository.findByOperationId(operationId)?.also {
            if (it.action != action || it.requestFingerprint != requestFingerprint || it.targetType != TARGET_TYPE) {
                throw DailyWordOperationConflictException()
            }
        }

    private fun audit(
        operationId: UUID,
        adminId: UUID,
        action: AdminAuditAction,
        targetId: UUID,
        reason: String,
        requestFingerprint: String,
        details: String,
    ) {
        auditRepository.save(
            AdminAuditLogEntity(
                operationId = operationId,
                adminUserId = adminId,
                action = action,
                targetType = TARGET_TYPE,
                targetId = targetId,
                reason = reason,
                details = details,
                requestFingerprint = requestFingerprint,
                createdAt = Instant.now(clock),
            ),
        )
    }

    private fun fingerprint(action: String, canonical: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$action|$canonical".toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun com.example.mypasture.bible.domain.BibleVerseEntity.toCitation() = BibleCitation(
        verseId = id,
        version = version,
        book = book.name,
        chapter = chapter,
        verse = verse,
        text = text,
    )

    companion object {
        private val SEOUL = ZoneId.of("Asia/Seoul")
        private val ACTIVE_JOB_STATUSES = listOf(DailyWordGenerationJobStatus.PENDING, DailyWordGenerationJobStatus.PROCESSING)
        private const val TARGET_TYPE = "DAILY_WORD"
    }
}

class DailyWordNotPublishedException(date: LocalDate) : RuntimeException("$date 오늘의 말씀이 아직 게시되지 않았습니다.")
class DailyWordNotFoundException(message: String) : RuntimeException(message)
class DailyWordConflictException(message: String) : RuntimeException(message)
class DailyWordGenerationException(message: String) : RuntimeException(message)
class DailyWordManualOverrideException : RuntimeException("수동 복구 초안은 AI 생성 결과로 덮어쓸 수 없습니다.")
class DailyWordOperationConflictException : RuntimeException("operationId가 다른 오늘의 말씀 관리자 요청에 이미 사용되었습니다.")
