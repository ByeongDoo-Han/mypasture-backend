package com.example.mypasture.bible.application

import com.example.mypasture.bible.domain.BibleCatalog
import com.example.mypasture.bible.domain.BibleCommentaryStatus
import com.example.mypasture.bible.infrastructure.BibleChapterCommentaryRepository
import org.springframework.stereotype.Service
import java.util.UUID

/** 책 단위 요청을 장별 독립 트랜잭션으로 분리해 일부 충돌이 전체 등록을 취소하지 않게 합니다. */
@Service
class BibleCommentaryBatchService(
    private val bibleReaderService: BibleReaderService,
    private val commentaryService: BibleCommentaryService,
    private val commentaryRepository: BibleChapterCommentaryRepository,
) {
    fun queueBookGeneration(
        version: String,
        bookCode: String,
        includePublished: Boolean,
        requestedBy: UUID,
    ): BibleCommentaryBookGenerationResult {
        val normalizedVersion = version.trim().uppercase()
        val book = BibleCatalog.findBook(bookCode)
            ?: throw BibleNotFoundException("Bible book was not found: $bookCode")
        bibleReaderService.getBooks(normalizedVersion)
        val queued = mutableListOf<Int>()
        val skipped = mutableListOf<Int>()
        val unavailable = mutableListOf<Int>()

        (1..book.chapterCount).forEach { chapter ->
            if (!includePublished && commentaryRepository.existsByVersionAndBookCodeAndChapterAndStatus(
                    normalizedVersion,
                    book.code,
                    chapter,
                    BibleCommentaryStatus.PUBLISHED,
                )
            ) {
                skipped += chapter
                return@forEach
            }
            try {
                commentaryService.queueGeneration(normalizedVersion, book.code, chapter, requestedBy)
                queued += chapter
            } catch (_: BibleCommentaryConflictException) {
                skipped += chapter
            } catch (_: BibleNotFoundException) {
                unavailable += chapter
            }
        }
        return BibleCommentaryBookGenerationResult(normalizedVersion, book.code, queued, skipped, unavailable)
    }
}
