package com.example.mypasture.daily_word.application

import com.example.mypasture.admin_audit.domain.AdminAuditAction
import com.example.mypasture.admin_audit.domain.AdminAuditLogEntity
import com.example.mypasture.admin_audit.infrastructure.AdminAuditLogRepository
import com.example.mypasture.ai.domain.BibleCitation
import com.example.mypasture.bible.infrastructure.BibleVerseEntityRepository
import com.example.mypasture.daily_word.domain.DailyWordEntity
import com.example.mypasture.daily_word.domain.DailyWordGenerationJobEntity
import com.example.mypasture.daily_word.domain.DailyWordGenerationJobStatus
import com.example.mypasture.daily_word.domain.DailyWordStatus
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
import java.util.UUID

/** 오늘의 말씀 생성 큐, 원문 재검증, 관리자 검수와 게시 규칙을 조합합니다. */
@Service
@Transactional(readOnly = true)
class DailyWordService(
    private val repository: DailyWordRepository,
    private val jobRepository: DailyWordGenerationJobRepository,
    private val verseRepository: BibleVerseEntityRepository,
    private val auditRepository: AdminAuditLogRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun getPublished(date: LocalDate = LocalDate.now(SEOUL)): DailyWordEntity =
        repository.findByWordDateAndStatus(date, DailyWordStatus.PUBLISHED)
            ?.also { verifyStoredVerse(it) }
            ?: throw DailyWordNotPublishedException(date)

    fun list(from: LocalDate, to: LocalDate, status: DailyWordStatus?): List<DailyWordEntity> {
        require(!to.isBefore(from)) { "조회 종료일은 시작일보다 빠를 수 없습니다." }
        require(!to.isAfter(from.plusDays(92))) { "조회 기간은 최대 93일입니다." }
        return repository.findForAdmin(from, to, status)
    }

    fun listJobs(): List<DailyWordGenerationJobEntity> = jobRepository.findAllByOrderByCreatedAtDesc().take(100)

    /** 스케줄러는 이미 콘텐츠나 활성 작업이 있으면 비용을 발생시키지 않고 건너뜁니다. */
    @Transactional
    fun queueScheduled(date: LocalDate): DailyWordGenerationJobEntity? {
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
        val normalizedReason = normalizeReason(reason)
        val fingerprint = fingerprint("QUEUE", "$date|$regenerate|$normalizedReason")
        existingJob(operationId, AdminAuditAction.DAILY_WORD_GENERATION_QUEUED, fingerprint)?.let { return it }
        if (hasActiveJob(date)) throw DailyWordConflictException("해당 날짜의 생성 작업이 이미 진행 중입니다.")
        val current = repository.findByWordDate(date)
        if (current != null && !regenerate) throw DailyWordConflictException("이미 콘텐츠가 있습니다. 재생성 여부를 확인해 주세요.")
        if (current?.status == DailyWordStatus.PUBLISHED) throw DailyWordConflictException("게시된 콘텐츠는 재생성할 수 없습니다.")

        val job = saveJob(DailyWordGenerationJobEntity(wordDate = date, requestedBy = adminId, requestReason = normalizedReason))
        audit(operationId, adminId, AdminAuditAction.DAILY_WORD_GENERATION_QUEUED, job.id, normalizedReason, fingerprint, "date=$date;regenerate=$regenerate")
        return job
    }

    /** lease를 보유한 워커만 RAG 결과를 실제 DB 절과 비교한 뒤 초안을 저장할 수 있습니다. */
    @Transactional
    fun saveGeneratedDraft(jobId: UUID, leaseToken: UUID, citation: BibleCitation, generated: GeneratedDailyWord): DailyWordEntity {
        val job = jobRepository.findByIdForUpdate(jobId) ?: throw DailyWordNotFoundException("생성 작업을 찾을 수 없습니다.")
        check(job.status == DailyWordGenerationJobStatus.PROCESSING && job.leaseToken == leaseToken) {
            "오늘의 말씀 생성 작업 lease가 유효하지 않습니다."
        }
        validateGenerated(generated)
        val source = verifiedVerse(citation)
        val now = Instant.now(clock)
        val word = repository.findByWordDateForUpdate(job.wordDate)?.also {
            check(it.status != DailyWordStatus.PUBLISHED) { "게시된 오늘의 말씀은 생성 작업으로 덮어쓸 수 없습니다." }
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
        val normalizedReason = normalizeReason(reason)
        val fingerprint = fingerprint("PUBLISH", "$id|$normalizedReason")
        existingWord(operationId, AdminAuditAction.DAILY_WORD_PUBLISHED, fingerprint)?.let { return it }
        val word = repository.findByIdForUpdate(id) ?: throw DailyWordNotFoundException("오늘의 말씀을 찾을 수 없습니다.")
        verifyStoredVerse(word)
        word.publish(adminId, Instant.now(clock))
        audit(operationId, adminId, AdminAuditAction.DAILY_WORD_PUBLISHED, word.id, normalizedReason, fingerprint, "date=${word.wordDate};revision=${word.contentRevision}")
        return word
    }

    @Transactional
    fun reject(id: UUID, adminId: UUID, operationId: UUID, reason: String): DailyWordEntity {
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

    private fun verifiedVerse(citation: BibleCitation) =
        verseRepository.findById(citation.verseId).orElseThrow { DailyWordGenerationException("RAG 절을 DB에서 찾을 수 없습니다.") }
            .also {
                if (it.version != citation.version || it.chapter != citation.chapter || it.verse != citation.verse || it.text != citation.text) {
                    throw DailyWordGenerationException("RAG 절이 현재 성경 원문과 일치하지 않습니다.")
                }
            }

    private fun verifyStoredVerse(word: DailyWordEntity) {
        val verseId = word.verseId ?: throw DailyWordGenerationException("오늘의 말씀이 실제 성경 절과 연결되지 않았습니다.")
        val source = verseRepository.findById(verseId).orElseThrow { DailyWordGenerationException("연결된 성경 절을 찾을 수 없습니다.") }
        if (
            source.version != word.bibleVersion || source.book.code != word.bookCode ||
            source.chapter != word.chapter || source.verse != word.verse || source.text != word.verseText
        ) throw DailyWordGenerationException("게시된 오늘의 말씀 본문이 현재 성경 원문과 일치하지 않습니다.")
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
        private const val TARGET_TYPE = "DAILY_WORD"
    }
}

class DailyWordNotPublishedException(date: LocalDate) : RuntimeException("$date 오늘의 말씀이 아직 게시되지 않았습니다.")
class DailyWordNotFoundException(message: String) : RuntimeException(message)
class DailyWordConflictException(message: String) : RuntimeException(message)
class DailyWordGenerationException(message: String) : RuntimeException(message)
class DailyWordOperationConflictException : RuntimeException("operationId가 다른 오늘의 말씀 관리자 요청에 이미 사용되었습니다.")
