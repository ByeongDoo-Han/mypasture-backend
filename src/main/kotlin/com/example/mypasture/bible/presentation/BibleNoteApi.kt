package com.example.mypasture.bible.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import java.time.Instant
import java.util.UUID

data class BibleNoteVerseRequest(
    @field:NotBlank
    @field:Size(max = 40)
    val version: String,
    @field:NotBlank
    @field:Size(max = 40)
    val bookCode: String,
    @field:Positive
    val chapter: Int,
    @field:Positive
    val verse: Int,
)

data class CreateBibleNoteRequest(
    @field:NotBlank
    @field:Size(max = 2000)
    val content: String,
    @field:Valid
    @field:Size(min = 1, max = 50)
    val verses: List<BibleNoteVerseRequest>,
)

data class UpdateBibleNoteRequest(
    @field:PositiveOrZero
    val expectedRevision: Long,
    @field:NotBlank
    @field:Size(max = 2000)
    val content: String,
    @field:Valid
    @field:Size(min = 1, max = 50)
    val verses: List<BibleNoteVerseRequest>,
)

data class BibleNoteVerseResponse(
    val version: String,
    val bookCode: String,
    val bookName: String,
    val chapter: Int,
    val verse: Int,
    val text: String,
)

data class BibleNoteResponse(
    val id: UUID,
    val revision: Long,
    val content: String,
    val verses: List<BibleNoteVerseResponse>,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class BibleNotePageResponse(
    val items: List<BibleNoteResponse>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)

/** Swagger/OpenAPI 문서와 구현을 분리한 사용자 말씀 메모 API 계약입니다. */
interface BibleNoteApi {
    @GetMapping
    fun notes(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): BibleNotePageResponse

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Valid @RequestBody request: CreateBibleNoteRequest,
    ): BibleNoteResponse

    @PutMapping("/{noteId}")
    fun update(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @PathVariable noteId: UUID,
        @Valid @RequestBody request: UpdateBibleNoteRequest,
    ): BibleNoteResponse

    @DeleteMapping("/{noteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @PathVariable noteId: UUID,
    )
}
