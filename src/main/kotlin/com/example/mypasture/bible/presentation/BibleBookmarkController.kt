package com.example.mypasture.bible.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.bible.application.BibleBookmarkService
import com.example.mypasture.bible.domain.BibleBookmarkEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 인증된 사용자의 절 단위 말씀 저장 기능을 모바일 앱에 제공합니다. */
@RestController
@RequestMapping("/api/v1/bookmarks")
class BibleBookmarkController(
    private val service: BibleBookmarkService,
) : BibleBookmarkApi {
    override fun status(
        principal: JwtPrincipal,
        version: String,
        bookCode: String,
        chapter: Int,
    ) = BibleBookmarkStatusResponse(service.findVerseNumbers(principal.userId, version, bookCode, chapter))

    override fun bookmarks(principal: JwtPrincipal, page: Int, size: Int): BibleBookmarkPageResponse {
        val bookmarks = service.findAll(principal.userId, page, size)
        return BibleBookmarkPageResponse(
            items = bookmarks.content.map { it.toResponse() },
            page = bookmarks.number,
            size = bookmarks.size,
            totalElements = bookmarks.totalElements,
            totalPages = bookmarks.totalPages,
        )
    }

    override fun save(principal: JwtPrincipal, version: String, bookCode: String, chapter: Int, verse: Int) =
        service.save(principal.userId, version, bookCode, chapter, verse).toResponse()

    override fun delete(principal: JwtPrincipal, version: String, bookCode: String, chapter: Int, verse: Int) =
        service.delete(principal.userId, version, bookCode, chapter, verse)
}

private fun BibleBookmarkEntity.toResponse() = BibleBookmarkResponse(
    id = id,
    version = verse.version,
    bookCode = verse.book.code,
    bookName = verse.book.name,
    chapter = verse.chapter,
    verse = verse.verse,
    text = verse.text,
    createdAt = createdAt,
)
