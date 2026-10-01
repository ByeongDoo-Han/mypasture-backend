package com.example.mypasture.bible.presentation

import com.example.mypasture.bible.application.BibleCommentaryService
import com.example.mypasture.bible.application.BibleReaderService
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 모바일/관리자 클라이언트의 성경 읽기와 게시된 해설 조회 요청을 받는 controller입니다. */
@RestController
@RequestMapping("/api/v1/bibles")
class BibleController(
    private val bibleReaderService: BibleReaderService,
    private val bibleCommentaryService: BibleCommentaryService,
) : BibleApi {
    override fun versions(): List<BibleVersionResponse> =
        bibleReaderService.getVersions().map { it.toResponse() }

    override fun books(version: String): List<BibleBookResponse> =
        bibleReaderService.getBooks(version).map { it.toResponse() }

    override fun search(version: String, keyword: String, page: Int, size: Int): BibleSearchResponse =
        bibleReaderService.search(version, keyword, page, size).toResponse()

    override fun searchCursor(version: String, keyword: String, size: Int, cursor: String?): BibleCursorSearchResponse =
        bibleReaderService.searchCursor(version, keyword, size, cursor).toResponse()

    override fun chapter(version: String, bookCode: String, chapter: Int): BibleChapterResponse =
        bibleReaderService.getChapter(version, bookCode, chapter).toResponse()

    override fun commentary(version: String, bookCode: String, chapter: Int): BibleChapterCommentaryResponse =
        bibleCommentaryService.getPublished(version, bookCode, chapter).toResponse()

    override fun verse(version: String, bookCode: String, chapter: Int, verse: Int): BibleVerseResponse =
        bibleReaderService.getVerse(version, bookCode, chapter, verse).toResponse()
}
