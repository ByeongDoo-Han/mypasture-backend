package com.example.mypasture.bible.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.bible.domain.ScriptureCopyStatus
import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import jakarta.validation.Valid
import jakarta.validation.constraints.*
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.UUID

/** 클라이언트 생성 ID로 재시도 가능한 필사 저장 요청입니다. */
data class ScriptureCopyRequest(
    @field:NotBlank @field:Size(max = 40) val version: String,
    @field:NotBlank @field:Size(max = 40) val bookCode: String,
    @field:Positive val chapter: Int,
    @field:Positive val verse: Int,
    @field:Size(max = 10000) val typedText: String,
    @field:Size(max = 1000) val reflection: String,
    @field:PositiveOrZero val expectedRevision: Long,
    val complete: Boolean,
    @field:Positive val endVerse: Int? = null,
)

/** 완료한 필사의 묵상만 변경하는 요청입니다. */
data class ScriptureCopyReflectionRequest(
    @field:Size(max = 1000) val reflection: String,
    @field:Positive val expectedRevision: Long,
)

/** 엔티티를 노출하지 않는 본인 필사 상세 응답입니다. */
data class ScriptureCopyResponse(
    val id: UUID, val version: String, val bookCode: String, val bookName: String, val chapter: Int, val verse: Int, val endVerse: Int,
    val sourceText: String, val typedText: String, val reflection: String, val revision: Long, val status: ScriptureCopyStatus,
    val createdAt: Instant, val updatedAt: Instant, val completedAt: Instant?,
)

/** 필사 목록의 페이지와 전체 개수를 제공합니다. */
data class ScriptureCopyPageResponse(
    val items: List<ScriptureCopyResponse>, val page: Int, val size: Int, val totalElements: Long, val totalPages: Int,
)

/** 본인 필사의 저장·조회·삭제 HTTP 계약을 정의합니다. */
@Tag(name = "성경 필사", description = "본인의 필사 진행 상태와 묵상을 저장합니다.")
interface ScriptureCopyApi {
    /** 서버 원문을 보존합니다. 동일 내용 재시도는 같은 revision이며, 변경은 expectedRevision 일치가 필요합니다. 완료 본문은 불변입니다. endVerse 생략 시 신규는 한 절, 기존은 저장 범위를 유지합니다. */
    @Operation(summary = "필사 진행 상태 저장", description = "서버 원문은 변경하지 않습니다. 동일 내용의 재시도는 같은 revision을 반환하며 변경 시 expectedRevision이 일치해야 합니다. 완료된 필사 본문은 수정할 수 없습니다.")
    @PutMapping("/{id}")
    fun save(@AuthenticationPrincipal principal: JwtPrincipal, @Parameter(description = "저장할 필사 기록 UUID.") @PathVariable id: UUID, @Valid @RequestBody request: ScriptureCopyRequest): ScriptureCopyResponse

    /** 완료 후 묵상만 저장하며 동일 묵상 재시도는 같은 revision을 반환합니다. */
    @Operation(summary = "완료 필사 묵상 수정", description = "필사 완료 후 묵상만 저장합니다. 동일한 묵상을 다시 저장하면 기존 revision을 반환합니다.")
    @PatchMapping("/{id}/reflection")
    fun updateReflection(@AuthenticationPrincipal principal: JwtPrincipal, @Parameter(description = "묵상을 변경할 본인 필사 기록 UUID.") @PathVariable id: UUID, @Valid @RequestBody request: ScriptureCopyReflectionRequest): ScriptureCopyResponse

    @Operation(summary = "내 필사 기록 목록", description = "로그인한 사용자의 필사 기록을 페이지로 조회합니다.")
    @GetMapping
    fun list(@AuthenticationPrincipal principal: JwtPrincipal, @Parameter(description = "0부터 시작하는 페이지 번호.") @RequestParam(defaultValue = "0") page: Int, @Parameter(description = "한 번에 반환할 최대 항목 수.") @RequestParam(defaultValue = "20") size: Int): ScriptureCopyPageResponse

    @Operation(summary = "내 필사 기록 조회", description = "본인이 작성한 필사 내용과 묵상을 조회합니다.")
    @GetMapping("/{id}")
    fun get(@AuthenticationPrincipal principal: JwtPrincipal, @Parameter(description = "조회할 본인 필사 기록 UUID.") @PathVariable id: UUID): ScriptureCopyResponse

    @Operation(summary = "내 필사 기록 삭제", description = "본인 기록을 expectedRevision이 일치할 때 삭제합니다.")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@AuthenticationPrincipal principal: JwtPrincipal, @Parameter(description = "삭제할 본인 필사 기록 UUID.") @PathVariable id: UUID, @Parameter(description = "삭제 대상 필사 기록의 현재 revision. 값이 다르면 충돌 오류가 발생합니다.") @RequestParam expectedRevision: Long)
}
