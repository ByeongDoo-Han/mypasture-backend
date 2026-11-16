package com.example.mypasture.bible.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
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
@Tag(name = "말씀 북마크", description = "로그인한 사용자의 성경 절 북마크를 관리합니다.")
interface BibleBookmarkApi {
    @Operation(summary = "한 장의 북마크 상태 조회", description = "선택한 장의 절별 북마크 상태를 로그인한 사용자 기준으로 반환합니다.")
    @GetMapping("/status")
    fun status(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Parameter(description = "북마크 상태를 확인할 성경 번역본 코드.") @RequestParam version: String,
        @Parameter(description = "성경 권 코드(예: GEN).") @RequestParam bookCode: String,
        @Parameter(description = "성경 장 번호(1부터 시작).") @RequestParam chapter: Int,
    ): BibleBookmarkStatusResponse

    @Operation(summary = "내 말씀 북마크 목록", description = "로그인한 사용자가 저장한 성경 절을 페이지로 조회합니다.")
    @GetMapping
    fun bookmarks(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Parameter(description = "0부터 시작하는 페이지 번호.") @RequestParam(defaultValue = "0") page: Int,
        @Parameter(description = "한 번에 반환할 최대 항목 수.") @RequestParam(defaultValue = "50") size: Int,
    ): BibleBookmarkPageResponse

    @Operation(summary = "말씀 북마크 저장", description = "선택한 성경 절을 내 북마크에 저장합니다. 같은 절을 다시 저장해도 중복 생성하지 않습니다.")
    @PutMapping("/{version}/{bookCode}/{chapter}/{verse}")
    fun save(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Parameter(description = "성경 번역본 코드(예: KOR1910).") @PathVariable version: String,
        @Parameter(description = "성경 권 코드(예: GEN).") @PathVariable bookCode: String,
        @Parameter(description = "성경 장 번호(1부터 시작).") @PathVariable chapter: Int,
        @Parameter(description = "성경 절 번호(1부터 시작).") @PathVariable verse: Int,
    ): BibleBookmarkResponse

    @Operation(summary = "말씀 북마크 삭제", description = "선택한 성경 절의 내 북마크를 삭제합니다.")
    @DeleteMapping("/{version}/{bookCode}/{chapter}/{verse}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Parameter(description = "성경 번역본 코드(예: KOR1910).") @PathVariable version: String,
        @Parameter(description = "성경 권 코드(예: GEN).") @PathVariable bookCode: String,
        @Parameter(description = "성경 장 번호(1부터 시작).") @PathVariable chapter: Int,
        @Parameter(description = "성경 절 번호(1부터 시작).") @PathVariable verse: Int,
    )
}
