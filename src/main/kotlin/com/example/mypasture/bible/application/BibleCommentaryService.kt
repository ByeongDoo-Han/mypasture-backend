package com.example.mypasture.bible.application

import com.example.mypasture.bible.domain.BibleChapter
import com.example.mypasture.bible.domain.BibleChapterCommentaryEntity
import com.example.mypasture.bible.domain.BibleCommentaryGenerationJobEntity
import com.example.mypasture.bible.domain.BibleCommentaryItemEntity
import com.example.mypasture.bible.domain.BibleCommentaryItemType
import com.example.mypasture.bible.domain.BibleCommentaryJobStatus
import com.example.mypasture.bible.domain.BibleCommentaryReferenceEntity
import com.example.mypasture.bible.domain.BibleCommentarySectionEntity
import com.example.mypasture.bible.domain.BibleCommentaryStatus
import com.example.mypasture.bible.infrastructure.BibleChapterCommentaryRepository
import com.example.mypasture.bible.infrastructure.BibleCommentaryGenerationJobRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** 장별 해설 생성 큐, 자동 검증, 검수와 게시 규칙을 조합하는 application service입니다. */
@Service
@Transactional(readOnly = true)
class BibleCommentaryService(
    private val bibleReaderService: BibleReaderService,
    private val commentaryRepository: BibleChapterCommentaryRepository,
    private val jobRepository: BibleCommentaryGenerationJobRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun getPublished(version: String, bookCode: String, chapter: Int): BibleChapterCommentary {
        val sourceChapter = bibleReaderService.getChapter(version, bookCode, chapter)
        val entity = commentaryRepository
            .findFirstByVersionAndBookCodeAndChapterAndStatusOrderByRevisionDesc(
                sourceChapter.version,
                sourceChapter.book.code,
                sourceChapter.chapter,
                BibleCommentaryStatus.PUBLISHED,
            ) ?: throw BibleCommentaryNotFoundException(sourceChapter.book.name, chapter)
        if (entity.sourceHash != sourceChapter.sourceHash()) {
            throw BibleCommentaryNotFoundException(sourceChapter.book.name, chapter)
        }
        return entity.toModel(sourceChapter.book.name)
    }

    fun listCommentaries(): List<BibleChapterCommentary> =
        commentaryRepository.findAllByOrderByCreatedAtDesc().map { entity ->
            val bookName = bibleReaderService.getBooks(entity.version).firstOrNull { it.code == entity.bookCode }?.name
                ?: entity.bookCode
            entity.toModel(bookName)
        }

    fun listGenerationJobs(): List<BibleCommentaryGenerationJob> =
        jobRepository.findAllByOrderByCreatedAtDesc().map { it.toModel() }

    @Transactional
    fun queueGeneration(version: String, bookCode: String, chapter: Int, requestedBy: UUID): BibleCommentaryGenerationJob {
        val sourceChapter = bibleReaderService.getChapter(version, bookCode, chapter)
        val activeStatuses = listOf(BibleCommentaryJobStatus.PENDING, BibleCommentaryJobStatus.PROCESSING)
        if (jobRepository.existsByVersionAndBookCodeAndChapterAndStatusIn(
                sourceChapter.version,
                sourceChapter.book.code,
                sourceChapter.chapter,
                activeStatuses,
            )
        ) {
            throw BibleCommentaryConflictException("이미 생성 대기 중인 장입니다.")
        }

        try {
            return jobRepository.saveAndFlush(
                BibleCommentaryGenerationJobEntity(
                    version = sourceChapter.version,
                    bookCode = sourceChapter.book.code,
                    chapter = sourceChapter.chapter,
                    requestedBy = requestedBy,
                ),
            ).toModel()
        } catch (exception: DataIntegrityViolationException) {
            // 사전 조회를 동시에 통과한 요청도 DB 유니크 인덱스가 마지막으로 차단합니다.
            throw BibleCommentaryConflictException("이미 생성 대기 중인 장입니다.")
        }
    }

    @Transactional
    fun saveGeneratedDraft(jobId: UUID, generated: GeneratedBibleCommentary, promptVersion: String): BibleChapterCommentary {
        val job = jobRepository.findById(jobId).orElseThrow { BibleCommentaryNotFoundException("생성 작업", 0) }
        check(job.status == BibleCommentaryJobStatus.PROCESSING) { "처리 중인 생성 작업만 저장할 수 있습니다." }

        val sourceChapter = bibleReaderService.getChapter(job.version, job.bookCode, job.chapter)
        validateGeneratedCommentary(sourceChapter, generated)
        val revision = commentaryRepository
            .findFirstByVersionAndBookCodeAndChapterOrderByRevisionDesc(job.version, job.bookCode, job.chapter)
            ?.revision
            ?.plus(1)
            ?: 1
        val now = Instant.now(clock)
        val entity = BibleChapterCommentaryEntity(
            generationJobId = job.id,
            version = sourceChapter.version,
            bookCode = sourceChapter.book.code,
            chapter = sourceChapter.chapter,
            revision = revision,
            summary = generated.summary.trim(),
            historicalContext = generated.historicalContext.trim(),
            sourceHash = sourceChapter.sourceHash(),
            promptVersion = promptVersion,
            model = generated.model,
            promptTokens = generated.promptTokens,
            completionTokens = generated.completionTokens,
            estimatedCostUsd = generated.estimatedCostUsd,
            createdAt = now,
            updatedAt = now,
        )

        generated.sections.forEachIndexed { index, section ->
            entity.sections += BibleCommentarySectionEntity(
                commentary = entity,
                sectionOrder = index,
                startVerse = section.startVerse,
                endVerse = section.endVerse,
                title = section.title.trim(),
                explanation = section.explanation.trim(),
            )
        }
        entity.addItems(BibleCommentaryItemType.KEY_THEME, generated.keyThemes)
        entity.addItems(BibleCommentaryItemType.REFLECTION_QUESTION, generated.reflectionQuestions)
        entity.addItems(BibleCommentaryItemType.CAUTION, generated.cautions)
        generated.crossReferences.forEachIndexed { index, reference ->
            entity.references += BibleCommentaryReferenceEntity(
                commentary = entity,
                referenceOrder = index,
                bookCode = reference.bookCode.uppercase(),
                chapter = reference.chapter,
                verse = reference.verse,
                reason = reference.reason.trim(),
            )
        }

        val saved = commentaryRepository.save(entity)
        job.complete(saved.id, now)
        return saved.toModel(sourceChapter.book.name)
    }

    @Transactional
    fun publish(commentaryId: UUID, reviewerId: UUID, qualityScore: Int): BibleChapterCommentary {
        require(qualityScore in 1..100) { "품질 점수는 1부터 100 사이여야 합니다." }
        val target = commentaryRepository.findById(commentaryId)
            .orElseThrow { BibleCommentaryNotFoundException("해설", 0) }
        check(target.status == BibleCommentaryStatus.DRAFT) { "초안 상태의 해설만 게시할 수 있습니다." }

        val now = Instant.now(clock)
        commentaryRepository.findFirstByVersionAndBookCodeAndChapterAndStatusOrderByRevisionDesc(
            target.version,
            target.bookCode,
            target.chapter,
            BibleCommentaryStatus.PUBLISHED,
        )?.supersede(now)
        target.publish(reviewerId, qualityScore, now)
        val bookName = bibleReaderService.getBooks(target.version).first { it.code == target.bookCode }.name
        return target.toModel(bookName)
    }

    @Transactional
    fun reject(commentaryId: UUID, reviewerId: UUID) {
        val target = commentaryRepository.findById(commentaryId)
            .orElseThrow { BibleCommentaryNotFoundException("해설", 0) }
        check(target.status == BibleCommentaryStatus.DRAFT) { "초안 상태의 해설만 거절할 수 있습니다." }
        target.reject(reviewerId, Instant.now(clock))
    }

    private fun validateGeneratedCommentary(sourceChapter: BibleChapter, generated: GeneratedBibleCommentary) {
        require(generated.summary.trim().length in 50..2000) { "해설 요약 길이가 올바르지 않습니다." }
        require(generated.historicalContext.trim().length in 20..3000) { "배경 설명 길이가 올바르지 않습니다." }
        require(generated.sections.isNotEmpty()) { "절 구간 해설이 필요합니다." }
        require(generated.keyThemes.size in 1..8) { "핵심 주제는 1개에서 8개까지 허용됩니다." }
        require(generated.crossReferences.size <= 10) { "교차 참조는 최대 10개까지 허용됩니다." }
        require(generated.reflectionQuestions.size in 1..6) { "묵상 질문은 1개에서 6개까지 허용됩니다." }
        require(generated.cautions.size <= 6) { "주의사항은 최대 6개까지 허용됩니다." }

        val maxVerse = sourceChapter.verses.maxOf { it.verse }
        val orderedSections = generated.sections.sortedBy { it.startVerse }
        require(orderedSections.first().startVerse == 1) { "해설 구간은 1절부터 시작해야 합니다." }
        orderedSections.forEachIndexed { index, section ->
            require(section.startVerse in 1..maxVerse && section.endVerse in section.startVerse..maxVerse) {
                "해설 구간이 실제 장의 절 범위를 벗어났습니다."
            }
            require(section.title.isNotBlank() && section.explanation.isNotBlank()) { "해설 구간 내용이 비어 있습니다." }
            if (index > 0) {
                require(section.startVerse == orderedSections[index - 1].endVerse + 1) {
                    "해설 구간은 중복이나 누락 없이 이어져야 합니다."
                }
            }
        }
        require(orderedSections.last().endVerse == maxVerse) { "해설 구간이 마지막 절까지 포함해야 합니다." }

        generated.crossReferences.forEach { reference ->
            bibleReaderService.getVerse(sourceChapter.version, reference.bookCode, reference.chapter, reference.verse)
            require(reference.reason.isNotBlank()) { "교차 참조 이유가 비어 있습니다." }
        }
    }

    private fun BibleChapterCommentaryEntity.addItems(type: BibleCommentaryItemType, values: List<String>) {
        values.forEachIndexed { index, value ->
            require(value.isNotBlank()) { "해설 항목이 비어 있습니다." }
            items += BibleCommentaryItemEntity(commentary = this, itemType = type, itemOrder = index, content = value.trim())
        }
    }

    private fun BibleChapter.sourceHash(): String {
        val source = verses.joinToString("\n") { "${it.verse}:${it.text}" }
        val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

private fun BibleChapterCommentaryEntity.toModel(bookName: String): BibleChapterCommentary =
    BibleChapterCommentary(
        id = id,
        version = version,
        bookCode = bookCode,
        bookName = bookName,
        chapter = chapter,
        revision = revision,
        status = status,
        summary = summary,
        historicalContext = historicalContext,
        sections = sections.sortedBy { it.sectionOrder }.map {
            GeneratedCommentarySection(it.startVerse, it.endVerse, it.title, it.explanation)
        },
        keyThemes = items.filter { it.itemType == BibleCommentaryItemType.KEY_THEME }.sortedBy { it.itemOrder }.map { it.content },
        crossReferences = references.sortedBy { it.referenceOrder }.map {
            GeneratedCommentaryReference(it.bookCode, it.chapter, it.verse, it.reason)
        },
        reflectionQuestions = items.filter { it.itemType == BibleCommentaryItemType.REFLECTION_QUESTION }
            .sortedBy { it.itemOrder }.map { it.content },
        cautions = items.filter { it.itemType == BibleCommentaryItemType.CAUTION }.sortedBy { it.itemOrder }.map { it.content },
        model = model,
        qualityScore = qualityScore,
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        estimatedCostUsd = estimatedCostUsd,
        publishedAt = publishedAt,
        createdAt = createdAt,
    )

private fun BibleCommentaryGenerationJobEntity.toModel(): BibleCommentaryGenerationJob =
    BibleCommentaryGenerationJob(
        id = id,
        version = version,
        bookCode = bookCode,
        chapter = chapter,
        status = status,
        attemptCount = attemptCount,
        errorMessage = errorMessage,
        commentaryId = commentaryId,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

/** 게시된 장별 해설이나 관리자 대상 초안을 찾을 수 없을 때 발생합니다. */
class BibleCommentaryNotFoundException(label: String, chapter: Int) :
    RuntimeException(if (chapter > 0) "$label ${chapter}장의 게시된 해설을 찾을 수 없습니다." else "${label}을 찾을 수 없습니다.")

/** 동일한 장의 생성 작업이 이미 진행 중일 때 중복 요청을 차단합니다. */
class BibleCommentaryConflictException(message: String) : RuntimeException(message)
