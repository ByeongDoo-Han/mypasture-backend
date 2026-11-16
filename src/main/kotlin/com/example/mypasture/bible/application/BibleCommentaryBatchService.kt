package com.example.mypasture.bible.application

import com.example.mypasture.bible.domain.BibleBook
import com.example.mypasture.bible.domain.BibleCatalog
import com.example.mypasture.bible.domain.BibleChapter
import com.example.mypasture.bible.domain.BibleChapterContentStatus
import com.example.mypasture.bible.domain.BibleChapterCommentaryEntity
import com.example.mypasture.bible.domain.BibleCommentaryGenerationJobEntity
import com.example.mypasture.bible.domain.BibleCommentaryJobStatus
import com.example.mypasture.bible.domain.BibleCommentaryStatus
import com.example.mypasture.bible.infrastructure.BibleChapterCommentaryRepository
import com.example.mypasture.bible.infrastructure.BibleCommentaryGenerationJobRepository
import org.springframework.stereotype.Service
import java.util.UUID

/** 책·장 범위의 사전 생성 등록과 현재 적재 현황을 관리합니다. */
@Service
class BibleCommentaryBatchService(
    private val bibleReaderService: BibleReaderService,
    private val commentaryService: BibleCommentaryService,
    private val commentaryRepository: BibleChapterCommentaryRepository,
    private val jobRepository: BibleCommentaryGenerationJobRepository,
) {
    fun queueBookGeneration(
        version: String,
        bookCode: String,
        includePublished: Boolean,
        requestedBy: UUID,
    ): BibleCommentaryBookGenerationResult {
        val (normalizedVersion, book) = requireBook(version, bookCode)
        val result = queueChapters(normalizedVersion, book, 1..book.chapterCount, includePublished, requestedBy)
        return BibleCommentaryBookGenerationResult(
            normalizedVersion, book.code, result.queuedChapters, result.skippedChapters, result.unavailableChapters,
        )
    }

    fun queueRangeGeneration(
        version: String,
        bookCode: String,
        startChapter: Int,
        endChapter: Int,
        includePublished: Boolean,
        requestedBy: UUID,
    ): BibleCommentaryRangeGenerationResult {
        val (normalizedVersion, book) = requireBook(version, bookCode)
        require(startChapter in 1..book.chapterCount && endChapter in startChapter..book.chapterCount) {
            "생성할 장 범위가 올바르지 않습니다."
        }
        require(endChapter - startChapter + 1 <= MAX_CHAPTERS_PER_REQUEST) {
            "한 번에 최대 ${MAX_CHAPTERS_PER_REQUEST}개 장을 등록할 수 있습니다."
        }
        return queueChapters(normalizedVersion, book, startChapter..endChapter, includePublished, requestedBy)
    }

    fun getBookCoverage(version: String, bookCode: String): BibleCommentaryBookCoverage {
        val (normalizedVersion, book) = requireBook(version, bookCode)
        val snapshot = loadSnapshot(normalizedVersion, book.code)
        val chapters = (1..book.chapterCount).map { chapter ->
            val source = availableChapter(normalizedVersion, book.code, chapter)
            if (source == null) {
                BibleCommentaryChapterCoverage(chapter, BibleCommentaryCoverageStatus.UNAVAILABLE, null, null, null)
            } else {
                val state = snapshot.state(chapter, source)
                val status = when {
                    state.activeJob?.status == BibleCommentaryJobStatus.PROCESSING -> BibleCommentaryCoverageStatus.PROCESSING
                    state.activeJob != null -> BibleCommentaryCoverageStatus.PENDING
                    state.published != null -> BibleCommentaryCoverageStatus.PUBLISHED
                    state.draft != null -> BibleCommentaryCoverageStatus.DRAFT
                    state.hasStaleCommentary -> BibleCommentaryCoverageStatus.STALE
                    state.latestCommentary?.status == BibleCommentaryStatus.REJECTED -> BibleCommentaryCoverageStatus.REJECTED
                    state.latestJob?.status == BibleCommentaryJobStatus.FAILED -> BibleCommentaryCoverageStatus.FAILED
                    else -> BibleCommentaryCoverageStatus.MISSING
                }
                BibleCommentaryChapterCoverage(
                    chapter, status, state.published?.revision, state.draft?.revision, state.latestJob?.status,
                )
            }
        }
        return BibleCommentaryBookCoverage(normalizedVersion, book.code, chapters)
    }

    private fun queueChapters(
        version: String,
        book: BibleBook,
        range: IntRange,
        includePublished: Boolean,
        requestedBy: UUID,
    ): BibleCommentaryRangeGenerationResult {
        val snapshot = loadSnapshot(version, book.code)
        val queued = mutableListOf<Int>()
        val skipped = mutableListOf<Int>()
        val unavailable = mutableListOf<Int>()

        range.forEach { chapter ->
            val source = availableChapter(version, book.code, chapter)
            if (source == null) {
                unavailable += chapter
                return@forEach
            }
            val state = snapshot.state(chapter, source)
            if (state.activeJob != null || state.draft != null || (!includePublished && state.published != null)) {
                skipped += chapter
                return@forEach
            }
            try {
                // 단일 장 작업은 별도 트랜잭션으로 저장되어 다른 장의 충돌이 성공분을 취소하지 않습니다.
                commentaryService.queueGeneration(version, book.code, chapter, requestedBy)
                queued += chapter
            } catch (_: BibleCommentaryConflictException) {
                skipped += chapter
            } catch (_: BibleNotFoundException) {
                unavailable += chapter
            }
        }
        return BibleCommentaryRangeGenerationResult(version, book.code, range.first, range.last, queued, skipped, unavailable)
    }

    private fun requireBook(version: String, bookCode: String): Pair<String, BibleBook> {
        val normalizedVersion = version.trim().uppercase()
        val book = BibleCatalog.findBook(bookCode)
            ?: throw BibleNotFoundException("Bible book was not found: $bookCode")
        bibleReaderService.getBooks(normalizedVersion)
        return normalizedVersion to book
    }

    private fun availableChapter(version: String, bookCode: String, chapter: Int): BibleChapter? =
        try {
            bibleReaderService.getChapter(version, bookCode, chapter)
                .takeIf { it.contentStatus == BibleChapterContentStatus.AVAILABLE && it.verses.isNotEmpty() }
        } catch (_: BibleNotFoundException) {
            null
        }

    private fun loadSnapshot(version: String, bookCode: String) = BookSnapshot(
        commentaryRepository.findAllByVersionAndBookCode(version, bookCode).groupBy { it.chapter },
        jobRepository.findAllByVersionAndBookCode(version, bookCode).groupBy { it.chapter },
    )

    private data class BookSnapshot(
        val commentaries: Map<Int, List<BibleChapterCommentaryEntity>>,
        val jobs: Map<Int, List<BibleCommentaryGenerationJobEntity>>,
    ) {
        fun state(chapter: Int, source: BibleChapter): ChapterState {
            val allCommentaries = commentaries[chapter].orEmpty()
            val sourceHash = source.sourceHash()
            val current = allCommentaries.filter { it.sourceHash == sourceHash }
            val allJobs = jobs[chapter].orEmpty()
            return ChapterState(
                published = current.filter { it.status == BibleCommentaryStatus.PUBLISHED }.maxByOrNull { it.revision },
                draft = current.filter { it.status == BibleCommentaryStatus.DRAFT }.maxByOrNull { it.revision },
                activeJob = allJobs.filter {
                    it.status == BibleCommentaryJobStatus.PENDING || it.status == BibleCommentaryJobStatus.PROCESSING
                }.maxByOrNull { it.createdAt },
                latestJob = allJobs.maxByOrNull { it.createdAt },
                latestCommentary = allCommentaries.maxByOrNull { it.revision },
                hasStaleCommentary = current.isEmpty() && allCommentaries.isNotEmpty(),
            )
        }
    }

    private data class ChapterState(
        val published: BibleChapterCommentaryEntity?,
        val draft: BibleChapterCommentaryEntity?,
        val activeJob: BibleCommentaryGenerationJobEntity?,
        val latestJob: BibleCommentaryGenerationJobEntity?,
        val latestCommentary: BibleChapterCommentaryEntity?,
        val hasStaleCommentary: Boolean,
    )

    private companion object {
        const val MAX_CHAPTERS_PER_REQUEST = 50
    }
}
