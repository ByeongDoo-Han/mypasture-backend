package com.example.mypasture.bible.presentation

import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam

/** Swagger/OpenAPI 문서화를 controller 구현과 분리하기 위한 성경 API contract입니다. */
@Tag(name = "성경", description = "번역본과 성경 본문·검색·공개 해설을 조회합니다.")
interface BibleApi {
    @Operation(summary = "성경 번역본 목록", description = "서비스에서 조회할 수 있는 성경 번역본 목록을 반환합니다.")
    @GetMapping("/versions")
    fun versions(): List<BibleVersionResponse>

    @Operation(summary = "번역본의 성경 권 목록", description = "선택한 번역본에 포함된 성경 권과 장 정보를 반환합니다.")
    @GetMapping("/{version}/books")
    fun books(@Parameter(description = "성경 번역본 코드(예: KOR1910).") @PathVariable version: String): List<BibleBookResponse>

    @Operation(summary = "성경 본문 검색", description = "선택한 번역본에서 검색어에 맞는 말씀을 페이지 단위로 반환합니다.")
    @GetMapping("/{version}/search")
    fun search(
        @Parameter(description = "성경 번역본 코드(예: KOR1910).") @PathVariable version: String,
        @Parameter(description = "권 이름 또는 본문에서 찾을 검색어.") @RequestParam keyword: String,
        @Parameter(description = "0부터 시작하는 페이지 번호.") @RequestParam(defaultValue = "0") page: Int,
        @Parameter(description = "한 번에 반환할 최대 항목 수.") @RequestParam(defaultValue = "20") size: Int,
    ): BibleSearchResponse

    /** 권 이름과 본문 포함 결과를 반환하며 cursor가 있으면 마지막 위치 다음부터 탐색합니다. */
    @Operation(summary = "성경 본문 커서 검색", description = "권 이름과 본문에 맞는 결과를 반환합니다. cursor를 전달하면 직전 결과의 마지막 위치 다음부터 조회합니다.")
    @GetMapping("/{version}/search/cursor")
    fun searchCursor(
        @Parameter(description = "성경 번역본 코드(예: KOR1910).") @PathVariable version: String,
        @Parameter(description = "권 이름 또는 본문에서 찾을 검색어.") @RequestParam keyword: String,
        @Parameter(description = "한 번에 반환할 최대 항목 수.") @RequestParam(defaultValue = "20") size: Int,
        @Parameter(description = "다음 결과를 조회할 커서. 첫 요청에서는 생략합니다.") @RequestParam(required = false) cursor: String?,
    ): BibleCursorSearchResponse

    @Operation(summary = "성경 한 장 조회", description = "번역본·권·장에 해당하는 본문 전체를 반환합니다.")
    @GetMapping("/{version}/books/{bookCode}/chapters/{chapter}")
    fun chapter(
        @Parameter(description = "성경 번역본 코드(예: KOR1910).") @PathVariable version: String,
        @Parameter(description = "성경 권 코드(예: GEN).") @PathVariable bookCode: String,
        @Parameter(description = "성경 장 번호(1부터 시작).") @PathVariable chapter: Int,
    ): BibleChapterResponse

    @Operation(summary = "공개 장별 해설 조회", description = "선택한 장에 게시된 해설이 있으면 반환합니다. 미게시 초안은 공개하지 않습니다.")
    @GetMapping("/{version}/books/{bookCode}/chapters/{chapter}/commentary")
    fun commentary(
        @Parameter(description = "성경 번역본 코드(예: KOR1910).") @PathVariable version: String,
        @Parameter(description = "성경 권 코드(예: GEN).") @PathVariable bookCode: String,
        @Parameter(description = "성경 장 번호(1부터 시작).") @PathVariable chapter: Int,
    ): BibleChapterCommentaryResponse

    @Operation(summary = "성경 한 절 조회", description = "번역본·권·장·절에 해당하는 본문을 반환합니다.")
    @GetMapping("/{version}/books/{bookCode}/chapters/{chapter}/verses/{verse}")
    fun verse(
        @Parameter(description = "성경 번역본 코드(예: KOR1910).") @PathVariable version: String,
        @Parameter(description = "성경 권 코드(예: GEN).") @PathVariable bookCode: String,
        @Parameter(description = "성경 장 번호(1부터 시작).") @PathVariable chapter: Int,
        @Parameter(description = "성경 절 번호(1부터 시작).") @PathVariable verse: Int,
    ): BibleVerseResponse
}
