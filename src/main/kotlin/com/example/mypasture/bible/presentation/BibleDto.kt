package com.example.mypasture.bible.presentation

import com.example.mypasture.bible.application.BibleChapterCommentary
import com.example.mypasture.bible.domain.BibleBook
import com.example.mypasture.bible.domain.BibleChapter
import com.example.mypasture.bible.domain.BibleChapterPointer
import com.example.mypasture.bible.domain.BibleSearchResult
import com.example.mypasture.bible.domain.BibleVerse
import com.example.mypasture.bible.domain.BibleVersion
import com.example.mypasture.bible.domain.BibleCursorSearchResult
import java.time.Instant
import java.util.UUID

/** 성경 번역본 목록 API 응답 DTO입니다. */
data class BibleVersionResponse(val code: String, val name: String, val language: String, val copyright: String)

/** 성경 권 목록 API 응답 DTO입니다. */
data class BibleBookResponse(
    val code: String,
    val name: String,
    val testament: String,
    val displayOrder: Int,
    val chapterCount: Int,
)

/** 한 장 전체 조회 API에서 이전/다음 장 이동 정보를 내려주는 DTO입니다. */
data class BibleChapterPointerResponse(val bookCode: String, val bookName: String, val chapter: Int)

/** 성경 절 본문 API 응답 DTO입니다. */
data class BibleVerseResponse(
    val version: String,
    val bookCode: String,
    val bookName: String,
    val chapter: Int,
    val verse: Int,
    val text: String,
)

/** cursor 검색은 전체 건수 없이 권 이름과 다음 본문 페이지의 존재 여부를 반환합니다. */
data class BibleCursorSearchResponse(
    val version: String,
    val keyword: String,
    val size: Int,
    val books: List<BibleBookResponse>,
    val results: List<BibleVerseResponse>,
    val hasNext: Boolean,
    val nextCursor: String?,
)

/** 한 장 전체 조회 API 응답 DTO입니다. */
data class BibleChapterResponse(
    val version: String,
    val book: BibleBookResponse,
    val chapter: Int,
    val contentStatus: String,
    val previous: BibleChapterPointerResponse?,
    val next: BibleChapterPointerResponse?,
    val verses: List<BibleVerseResponse>,
)

/** 단어 검색 API 응답 DTO입니다. */
data class BibleSearchResponse(
    val version: String,
    val keyword: String,
    val page: Int,
    val size: Int,
    val total: Int,
    val hasNext: Boolean,
    val results: List<BibleVerseResponse>,
)

/** 한 장 안의 연속된 절 범위를 설명하는 게시 해설 DTO입니다. */
data class BibleCommentarySectionResponse(
    val startVerse: Int,
    val endVerse: Int,
    val title: String,
    val explanation: String,
)

/** 실제 성경 원문을 클라이언트가 다시 조회할 수 있는 교차 참조 DTO입니다. */
data class BibleCommentaryReferenceResponse(
    val bookCode: String,
    val chapter: Int,
    val verse: Int,
    val reason: String,
)

/** 검수 후 게시된 최신 장별 AI 해설 응답 DTO입니다. */
data class BibleChapterCommentaryResponse(
    val id: UUID,
    val version: String,
    val bookCode: String,
    val bookName: String,
    val chapter: Int,
    val revision: Int,
    val summary: String,
    val historicalContext: String,
    val sections: List<BibleCommentarySectionResponse>,
    val keyThemes: List<String>,
    val crossReferences: List<BibleCommentaryReferenceResponse>,
    val reflectionQuestions: List<String>,
    val cautions: List<String>,
    val publishedAt: Instant?,
)

fun BibleVersion.toResponse(): BibleVersionResponse = BibleVersionResponse(code, name, language, copyright)

fun BibleBook.toResponse(): BibleBookResponse =
    BibleBookResponse(code, name, testament.name, displayOrder, chapterCount)

fun BibleVerse.toResponse(): BibleVerseResponse =
    BibleVerseResponse(version, bookCode, bookName, chapter, verse, text)

fun BibleChapter.toResponse(): BibleChapterResponse =
    BibleChapterResponse(
        version,
        book.toResponse(),
        chapter,
        contentStatus.name,
        previous?.toResponse(),
        next?.toResponse(),
        verses.map { it.toResponse() },
    )

fun BibleSearchResult.toResponse(): BibleSearchResponse =
    BibleSearchResponse(version, keyword, page, size, total, hasNext, verses.map { it.toResponse() })

fun BibleCursorSearchResult.toResponse() = BibleCursorSearchResponse(version, keyword, size, books.map { it.toResponse() }, verses.map { it.toResponse() }, hasNext, nextCursor)

fun BibleChapterCommentary.toResponse(): BibleChapterCommentaryResponse =
    BibleChapterCommentaryResponse(
        id = id,
        version = version,
        bookCode = bookCode,
        bookName = bookName,
        chapter = chapter,
        revision = revision,
        summary = summary,
        historicalContext = historicalContext,
        sections = sections.map { BibleCommentarySectionResponse(it.startVerse, it.endVerse, it.title, it.explanation) },
        keyThemes = keyThemes,
        crossReferences = crossReferences.map {
            BibleCommentaryReferenceResponse(it.bookCode, it.chapter, it.verse, it.reason)
        },
        reflectionQuestions = reflectionQuestions,
        cautions = cautions,
        publishedAt = publishedAt,
    )

private fun BibleChapterPointer.toResponse(): BibleChapterPointerResponse =
    BibleChapterPointerResponse(bookCode, bookName, chapter)
