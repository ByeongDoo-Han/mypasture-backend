package com.example.mypasture.bible.presentation

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam

/** Swagger/OpenAPI 문서화를 controller 구현과 분리하기 위한 성경 API contract입니다. */
interface BibleApi {
    @GetMapping("/versions")
    fun versions(): List<BibleVersionResponse>

    @GetMapping("/{version}/books")
    fun books(@PathVariable version: String): List<BibleBookResponse>

    @GetMapping("/{version}/search")
    fun search(
        @PathVariable version: String,
        @RequestParam keyword: String,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): BibleSearchResponse

    /** 권 이름과 본문 포함 결과를 반환하며 cursor가 있으면 마지막 위치 다음부터 탐색합니다. */
    @GetMapping("/{version}/search/cursor")
    fun searchCursor(
        @PathVariable version: String,
        @RequestParam keyword: String,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) cursor: String?,
    ): BibleCursorSearchResponse

    @GetMapping("/{version}/books/{bookCode}/chapters/{chapter}")
    fun chapter(
        @PathVariable version: String,
        @PathVariable bookCode: String,
        @PathVariable chapter: Int,
    ): BibleChapterResponse

    @GetMapping("/{version}/books/{bookCode}/chapters/{chapter}/commentary")
    fun commentary(
        @PathVariable version: String,
        @PathVariable bookCode: String,
        @PathVariable chapter: Int,
    ): BibleChapterCommentaryResponse

    @GetMapping("/{version}/books/{bookCode}/chapters/{chapter}/verses/{verse}")
    fun verse(
        @PathVariable version: String,
        @PathVariable bookCode: String,
        @PathVariable chapter: Int,
        @PathVariable verse: Int,
    ): BibleVerseResponse
}
