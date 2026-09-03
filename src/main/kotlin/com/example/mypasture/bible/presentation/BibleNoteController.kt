package com.example.mypasture.bible.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.bible.application.BibleNoteDetail
import com.example.mypasture.bible.application.BibleNoteService
import com.example.mypasture.bible.application.BibleNoteVerseSelection
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** 인증된 사용자의 다중 구절 메모를 모바일 앱에 제공하는 HTTP controller입니다. */
@RestController
@RequestMapping("/api/v1/bible-notes")
class BibleNoteController(
    private val service: BibleNoteService,
) : BibleNoteApi {
    override fun notes(principal: JwtPrincipal, page: Int, size: Int): BibleNotePageResponse {
        val notes = service.findAll(principal.userId, page, size)
        return BibleNotePageResponse(
            items = notes.items.map { it.toResponse() },
            page = notes.page,
            size = notes.size,
            totalElements = notes.totalElements,
            totalPages = notes.totalPages,
        )
    }

    override fun create(principal: JwtPrincipal, request: CreateBibleNoteRequest) =
        service.create(principal.userId, request.content, request.verses.toSelections()).toResponse()

    override fun update(principal: JwtPrincipal, noteId: UUID, request: UpdateBibleNoteRequest) =
        service.update(
            userId = principal.userId,
            noteId = noteId,
            expectedRevision = request.expectedRevision,
            content = request.content,
            selections = request.verses.toSelections(),
        ).toResponse()

    override fun delete(principal: JwtPrincipal, noteId: UUID) = service.delete(principal.userId, noteId)
}

private fun List<BibleNoteVerseRequest>.toSelections() = map {
    BibleNoteVerseSelection(
        version = it.version,
        bookCode = it.bookCode,
        chapter = it.chapter,
        verse = it.verse,
    )
}

private fun BibleNoteDetail.toResponse() = BibleNoteResponse(
    id = note.id,
    revision = note.revision,
    content = note.content,
    verses = verseLinks.sortedBy { it.sortOrder }.map { link ->
        BibleNoteVerseResponse(
            version = link.verse.version,
            bookCode = link.verse.book.code,
            bookName = link.verse.book.name,
            chapter = link.verse.chapter,
            verse = link.verse.verse,
            text = link.verse.text,
        )
    },
    createdAt = note.createdAt,
    updatedAt = note.updatedAt,
)
