package com.example.mypasture.bible.application

import com.example.mypasture.bible.domain.BibleChapter
import com.example.mypasture.bible.domain.BibleChapterContentStatus
import com.example.mypasture.bible.domain.BibleChapterCommentaryEntity
import com.example.mypasture.bible.domain.BibleCommentaryGenerationJobEntity
import com.example.mypasture.bible.domain.BibleCommentaryEvaluationStatus
import com.example.mypasture.bible.domain.BibleCommentaryItemEntity
import com.example.mypasture.bible.domain.BibleCommentaryItemType
import com.example.mypasture.bible.domain.BibleCommentaryJobStatus
import com.example.mypasture.bible.domain.BibleCommentaryReferenceEntity
import com.example.mypasture.bible.domain.BibleCommentaryReviewAction
import com.example.mypasture.bible.domain.BibleCommentaryReviewEventEntity
import com.example.mypasture.bible.domain.BibleCommentarySectionEntity
import com.example.mypasture.bible.domain.BibleCommentaryStatus
import com.example.mypasture.bible.infrastructure.BibleChapterCommentaryRepository
import com.example.mypasture.bible.infrastructure.BibleCommentaryGenerationJobRepository
import com.example.mypasture.bible.infrastructure.BibleCommentaryReviewEventRepository
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
    private val reviewEventRepository: BibleCommentaryReviewEventRepository,
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

    fun reviewEvents(commentaryId: UUID): List<BibleCommentaryReviewEvent> {
        requireCommentary(commentaryId)
        return reviewEventRepository.findAllByCommentaryIdOrderByCreatedAtAsc(commentaryId).map { it.toModel() }
    }

    @Transactional
    fun queueGeneration(version: String, bookCode: String, chapter: Int, requestedBy: UUID): BibleCommentaryGenerationJob {
        val sourceChapter = bibleReaderService.getChapter(version, bookCode, chapter)
        if (sourceChapter.contentStatus != BibleChapterContentStatus.AVAILABLE || sourceChapter.verses.isEmpty()) {
            throw BibleNotFoundException("해설을 생성할 성경 본문이 비어 있습니다: ${sourceChapter.book.code} $chapter")
        }
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
    fun saveGeneratedDraft(
        jobId: UUID,
        generated: GeneratedBibleCommentary,
        evaluation: BibleCommentaryEvaluation,
        promptVersion: String,
        evaluationThreshold: Int,
    ): BibleChapterCommentary {
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
            status = if (evaluation.passes(evaluationThreshold)) {
                BibleCommentaryStatus.DRAFT
            } else {
                BibleCommentaryStatus.REJECTED
            },
            evaluationStatus = if (evaluation.passes(evaluationThreshold)) {
                BibleCommentaryEvaluationStatus.PASSED
            } else {
                BibleCommentaryEvaluationStatus.FAILED
            },
            evaluatorModel = evaluation.model,
            evaluationScore = evaluation.minimumScore,
            evaluationGroundingScore = evaluation.groundingScore,
            evaluationHistoricalEvidenceScore = evaluation.historicalEvidenceScore,
            evaluationReferenceRelevanceScore = evaluation.referenceRelevanceScore,
            evaluationTheologicalNeutralityScore = evaluation.theologicalNeutralityScore,
            evaluationClarityScore = evaluation.clarityScore,
            fabricatedScripture = evaluation.fabricatedScripture,
            unsupportedHistoricalClaims = evaluation.unsupportedHistoricalClaims,
            evaluationIssues = evaluation.issues.toStoredIssues(),
            evaluationPromptTokens = evaluation.promptTokens,
            evaluationCompletionTokens = evaluation.completionTokens,
            evaluationCostUsd = evaluation.estimatedCostUsd,
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
    fun editDraft(commentaryId: UUID, reviewerId: UUID, edit: BibleCommentaryDraftEdit): BibleChapterCommentary {
        val previous = requireCommentaryForUpdate(commentaryId)
        check(previous.status == BibleCommentaryStatus.DRAFT || previous.status == BibleCommentaryStatus.REJECTED) {
            "초안 또는 거절 상태의 해설만 수정할 수 있습니다."
        }
        val notes = edit.reviewNotes.trim()
        require(notes.length in 5..1000) { "수정 사유는 5자에서 1000자 사이여야 합니다." }
        val sourceChapter = bibleReaderService.getChapter(previous.version, previous.bookCode, previous.chapter)
        val generated = edit.toGenerated(previous)
        validateGeneratedCommentary(sourceChapter, generated)
        val now = Instant.now(clock)
        val latest = commentaryRepository
            .findFirstByVersionAndBookCodeAndChapterOrderByRevisionDesc(previous.version, previous.bookCode, previous.chapter)!!
        check(latest.id == previous.id) { "최신 revision만 수정할 수 있습니다. 목록을 새로고침해 주세요." }
        val revision = latest.revision + 1
        previous.supersede(now)
        val edited = BibleChapterCommentaryEntity(
            generationJobId = previous.generationJobId,
            version = previous.version,
            bookCode = previous.bookCode,
            chapter = previous.chapter,
            revision = revision,
            status = BibleCommentaryStatus.DRAFT,
            summary = generated.summary.trim(),
            historicalContext = generated.historicalContext.trim(),
            sourceHash = sourceChapter.sourceHash(),
            promptVersion = previous.promptVersion,
            model = previous.model,
            promptTokens = previous.promptTokens,
            completionTokens = previous.completionTokens,
            estimatedCostUsd = previous.estimatedCostUsd,
            evaluationStatus = BibleCommentaryEvaluationStatus.MANUAL_REVIEW,
            evaluatorModel = previous.evaluatorModel,
            evaluationScore = previous.evaluationScore,
            evaluationGroundingScore = previous.evaluationGroundingScore,
            evaluationHistoricalEvidenceScore = previous.evaluationHistoricalEvidenceScore,
            evaluationReferenceRelevanceScore = previous.evaluationReferenceRelevanceScore,
            evaluationTheologicalNeutralityScore = previous.evaluationTheologicalNeutralityScore,
            evaluationClarityScore = previous.evaluationClarityScore,
            fabricatedScripture = previous.fabricatedScripture,
            unsupportedHistoricalClaims = previous.unsupportedHistoricalClaims,
            evaluationIssues = previous.evaluationIssues,
            evaluationPromptTokens = previous.evaluationPromptTokens,
            evaluationCompletionTokens = previous.evaluationCompletionTokens,
            evaluationCostUsd = previous.evaluationCostUsd,
            reviewNotes = notes,
            lastEditedBy = reviewerId,
            lastEditedAt = now,
            createdAt = now,
            updatedAt = now,
        )
        edited.replaceContent(generated)
        val saved = commentaryRepository.save(edited)
        reviewEventRepository.save(
            BibleCommentaryReviewEventEntity(
                commentaryId = saved.id,
                previousCommentaryId = previous.id,
                action = BibleCommentaryReviewAction.EDITED,
                reviewerId = reviewerId,
                notes = notes,
                createdAt = now,
            ),
        )
        return saved.toModel(sourceChapter.book.name)
    }

    @Transactional
    fun publish(commentaryId: UUID, reviewerId: UUID, qualityScore: Int, reviewNotes: String): BibleChapterCommentary {
        require(qualityScore in 1..100) { "품질 점수는 1부터 100 사이여야 합니다." }
        val notes = reviewNotes.trim()
        require(notes.length in 5..1000) { "게시 검수 의견은 5자에서 1000자 사이여야 합니다." }
        val target = requireCommentaryForUpdate(commentaryId)
        check(target.status == BibleCommentaryStatus.DRAFT) { "초안 상태의 해설만 게시할 수 있습니다." }
        check(target.evaluationStatus in setOf(BibleCommentaryEvaluationStatus.PASSED, BibleCommentaryEvaluationStatus.MANUAL_REVIEW)) {
            "자동 평가를 통과했거나 사람이 수정 검수한 해설만 게시할 수 있습니다."
        }

        val now = Instant.now(clock)
        commentaryRepository.findFirstByVersionAndBookCodeAndChapterAndStatusOrderByRevisionDesc(
            target.version,
            target.bookCode,
            target.chapter,
            BibleCommentaryStatus.PUBLISHED,
        )?.let { published ->
            published.supersede(now)
            commentaryRepository.flush()
        }
        target.publish(reviewerId, qualityScore, notes, now)
        reviewEventRepository.save(
            BibleCommentaryReviewEventEntity(
                commentaryId = target.id,
                action = BibleCommentaryReviewAction.PUBLISHED,
                reviewerId = reviewerId,
                notes = notes,
                createdAt = now,
            ),
        )
        val bookName = bibleReaderService.getBooks(target.version).first { it.code == target.bookCode }.name
        return target.toModel(bookName)
    }

    @Transactional
    fun reject(commentaryId: UUID, reviewerId: UUID, reviewNotes: String) {
        val notes = reviewNotes.trim()
        require(notes.length in 5..1000) { "반려 사유는 5자에서 1000자 사이여야 합니다." }
        val target = requireCommentaryForUpdate(commentaryId)
        check(target.status == BibleCommentaryStatus.DRAFT) { "초안 상태의 해설만 거절할 수 있습니다." }
        val now = Instant.now(clock)
        target.reject(reviewerId, notes, now)
        reviewEventRepository.save(
            BibleCommentaryReviewEventEntity(
                commentaryId = target.id,
                action = BibleCommentaryReviewAction.REJECTED,
                reviewerId = reviewerId,
                notes = notes,
                createdAt = now,
            ),
        )
    }

    private fun requireCommentary(commentaryId: UUID): BibleChapterCommentaryEntity =
        commentaryRepository.findById(commentaryId)
            .orElseThrow { BibleCommentaryNotFoundException("해설", 0) }

    private fun requireCommentaryForUpdate(commentaryId: UUID): BibleChapterCommentaryEntity =
        commentaryRepository.findByIdForUpdate(commentaryId)
            ?: throw BibleCommentaryNotFoundException("해설", 0)

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

    private fun BibleChapterCommentaryEntity.replaceContent(generated: GeneratedBibleCommentary) {
        generated.sections.forEachIndexed { index, section ->
            sections += BibleCommentarySectionEntity(
                commentary = this,
                sectionOrder = index,
                startVerse = section.startVerse,
                endVerse = section.endVerse,
                title = section.title.trim(),
                explanation = section.explanation.trim(),
            )
        }
        addItems(BibleCommentaryItemType.KEY_THEME, generated.keyThemes)
        addItems(BibleCommentaryItemType.REFLECTION_QUESTION, generated.reflectionQuestions)
        addItems(BibleCommentaryItemType.CAUTION, generated.cautions)
        generated.crossReferences.forEachIndexed { index, reference ->
            references += BibleCommentaryReferenceEntity(
                commentary = this,
                referenceOrder = index,
                bookCode = reference.bookCode.uppercase(),
                chapter = reference.chapter,
                verse = reference.verse,
                reason = reference.reason.trim(),
            )
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
        evaluationStatus = evaluationStatus,
        evaluatorModel = evaluatorModel,
        evaluationScore = evaluationScore,
        evaluationGroundingScore = evaluationGroundingScore,
        evaluationHistoricalEvidenceScore = evaluationHistoricalEvidenceScore,
        evaluationReferenceRelevanceScore = evaluationReferenceRelevanceScore,
        evaluationTheologicalNeutralityScore = evaluationTheologicalNeutralityScore,
        evaluationClarityScore = evaluationClarityScore,
        fabricatedScripture = fabricatedScripture,
        unsupportedHistoricalClaims = unsupportedHistoricalClaims,
        evaluationIssues = evaluationIssues.toIssueList(),
        evaluationPromptTokens = evaluationPromptTokens,
        evaluationCompletionTokens = evaluationCompletionTokens,
        evaluationCostUsd = evaluationCostUsd,
        reviewNotes = reviewNotes,
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        estimatedCostUsd = estimatedCostUsd,
        publishedAt = publishedAt,
        createdAt = createdAt,
    )

private fun BibleCommentaryReviewEventEntity.toModel(): BibleCommentaryReviewEvent =
    BibleCommentaryReviewEvent(
        id = requireNotNull(id),
        commentaryId = commentaryId,
        previousCommentaryId = previousCommentaryId,
        action = action,
        reviewerId = reviewerId,
        notes = notes,
        createdAt = createdAt,
    )

private fun List<String>.toStoredIssues(): String? =
    map { it.replace('\n', ' ').trim() }
        .filter { it.isNotEmpty() }
        .take(20)
        .joinToString("\n")
        .takeIf { it.isNotEmpty() }

private fun String?.toIssueList(): List<String> =
    this?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toList().orEmpty()

private fun BibleCommentaryDraftEdit.toGenerated(previous: BibleChapterCommentaryEntity): GeneratedBibleCommentary =
    GeneratedBibleCommentary(
        summary = summary,
        historicalContext = historicalContext,
        sections = sections,
        keyThemes = keyThemes,
        crossReferences = crossReferences,
        reflectionQuestions = reflectionQuestions,
        cautions = cautions,
        model = previous.model,
        promptTokens = previous.promptTokens,
        completionTokens = previous.completionTokens,
        estimatedCostUsd = previous.estimatedCostUsd,
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
