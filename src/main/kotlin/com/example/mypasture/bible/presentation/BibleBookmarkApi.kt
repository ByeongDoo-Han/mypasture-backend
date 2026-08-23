package com.example.mypasture.bible.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import java.time.Instant
import java.util.UUID

data class BibleBookmarkResponse(
    val id: UUID,
    val version: String,
    val bookCode: String,
    val bookName: String,
    val chapter: Int,
    val verse: Int,
    val text: String,
    val createdAt: Instant,
)

data class BibleBookmarkPageResponse(
    val items: List<BibleBookmarkResponse>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)

data class BibleBookmarkStatusResponse(
    val verseNumbers: List<Int>,
)

/** Swagger 문서와 HTTP 구현을 분리한 인증 사용자 말씀 저장 API 계약입니다. */
interface BibleBookmarkApi {
    @GetMapping("/status")
    fun status(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @RequestParam version: String,
        @RequestParam bookCode: String,
        @RequestParam chapter: Int,
    ): BibleBookmarkStatusResponse

    @GetMapping
    fun bookmarks(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): BibleBookmarkPageResponse

    @PutMapping("/{version}/{bookCode}/{chapter}/{verse}")
    fun save(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @PathVariable version: String,
        @PathVariable bookCode: String,
        @PathVariable chapter: Int,
        @PathVariable verse: Int,
    ): BibleBookmarkResponse

    @DeleteMapping("/{version}/{bookCode}/{chapter}/{verse}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @PathVariable version: String,
        @PathVariable bookCode: String,
        @PathVariable chapter: Int,
        @PathVariable verse: Int,
    )
}
