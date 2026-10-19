package com.example.mypasture.bible.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
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
@Tag(name = "말씀 메모", description = "본인의 성경 메모를 작성·조회·수정·삭제합니다.")
interface BibleNoteApi {
    @Operation(summary = "내 말씀 메모 목록", description = "로그인한 사용자의 말씀 메모만 페이지로 반환합니다.")
    @GetMapping
    fun notes(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Parameter(description = "0부터 시작하는 페이지 번호.") @RequestParam(defaultValue = "0") page: Int,
        @Parameter(description = "한 번에 반환할 최대 항목 수.") @RequestParam(defaultValue = "50") size: Int,
    ): BibleNotePageResponse

    @Operation(summary = "말씀 메모 작성", description = "선택한 말씀과 연결된 비공개 메모를 작성합니다.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Valid @RequestBody request: CreateBibleNoteRequest,
    ): BibleNoteResponse

    @Operation(summary = "내 말씀 메모 수정", description = "본인이 작성한 말씀 메모의 내용을 수정합니다.")
    @PutMapping("/{noteId}")
    fun update(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Parameter(description = "본인이 작성한 말씀 메모 UUID.") @PathVariable noteId: UUID,
        @Valid @RequestBody request: UpdateBibleNoteRequest,
    ): BibleNoteResponse

    @Operation(summary = "내 말씀 메모 삭제", description = "본인이 작성한 말씀 메모를 삭제합니다.")
    @DeleteMapping("/{noteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Parameter(description = "본인이 작성한 말씀 메모 UUID.") @PathVariable noteId: UUID,
    )
}
