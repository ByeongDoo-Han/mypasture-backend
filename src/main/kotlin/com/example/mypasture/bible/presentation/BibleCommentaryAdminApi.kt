package com.example.mypasture.bible.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestBody
import java.util.UUID

/** Swagger 문서와 구현을 분리한 장별 해설 생성·검수 관리자 API contract입니다. */
@Tag(name = "관리자 · 성경 해설", description = "장별 AI 해설의 생성·근거·검수·발행을 관리합니다.")
interface BibleCommentaryAdminApi {
    @Operation(summary = "장별 해설 목록 조회", description = "관리자가 생성된 장별 해설과 검수 상태를 조회합니다.")
    @GetMapping
    fun commentaries(): List<BibleCommentaryAdminResponse>

    @Operation(summary = "해설 생성 작업 조회", description = "관리자가 대기·진행·완료된 해설 생성 작업을 조회합니다.")
    @GetMapping("/generation-jobs")
    fun generationJobs(): List<BibleCommentaryGenerationJobResponse>

    @Operation(summary = "한 장 해설 생성 요청", description = "관리자가 지정한 성경 장의 해설 생성 작업을 대기열에 등록합니다.")
    @PostMapping("/generation-jobs")
    fun queueGeneration(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Valid @RequestBody request: QueueBibleCommentaryGenerationRequest,
    ): ResponseEntity<BibleCommentaryGenerationJobResponse>

    @Operation(summary = "한 권 해설 생성 요청", description = "관리자가 지정한 성경 권의 해설 생성 작업을 대기열에 등록합니다.")
    @PostMapping("/generation-jobs/books")
    fun queueBookGeneration(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Valid @RequestBody request: QueueBibleCommentaryBookGenerationRequest,
    ): ResponseEntity<BibleCommentaryBookGenerationResponse>

    @Operation(summary = "장 범위 해설 생성 요청", description = "관리자가 지정한 연속 장 범위의 해설 생성 작업을 대기열에 등록합니다.")
    @PostMapping("/generation-jobs/ranges")
    fun queueRangeGeneration(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Valid @RequestBody request: QueueBibleCommentaryRangeGenerationRequest,
    ): ResponseEntity<BibleCommentaryRangeGenerationResponse>

    @Operation(summary = "해설 범위 조회", description = "번역본과 성경 권의 장별 해설 생성·발행 범위를 조회합니다.")
    @GetMapping("/coverage")
    fun coverage(
        @Parameter(description = "성경 번역본 코드(예: KOR1910).") @RequestParam version: String,
        @Parameter(description = "성경 권 코드(예: GEN).") @RequestParam bookCode: String,
    ): BibleCommentaryBookCoverageResponse

    @Operation(summary = "장별 해설 수정", description = "관리자가 선택한 해설 초안을 수정합니다.")
    @PutMapping("/{commentaryId}")
    fun edit(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Parameter(description = "검수 또는 변경할 장별 해설 UUID.") @PathVariable commentaryId: UUID,
        @Valid @RequestBody request: EditBibleCommentaryRequest,
    ): BibleCommentaryAdminResponse

    @Operation(summary = "해설 검수 기록 조회", description = "선택한 해설의 검수 및 발행 판단 기록을 조회합니다.")
    @GetMapping("/{commentaryId}/reviews")
    fun reviews(@Parameter(description = "검수 또는 변경할 장별 해설 UUID.") @PathVariable commentaryId: UUID): List<BibleCommentaryReviewEventResponse>

    @Operation(summary = "장별 해설 발행", description = "관리자가 검수를 통과한 해설을 공개 상태로 전환합니다.")
    @PostMapping("/{commentaryId}/publish")
    fun publish(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Parameter(description = "검수 또는 변경할 장별 해설 UUID.") @PathVariable commentaryId: UUID,
        @Valid @RequestBody request: PublishBibleCommentaryRequest,
    ): BibleCommentaryAdminResponse

    @Operation(summary = "장별 해설 반려", description = "관리자가 해설 초안을 반려하고 사유를 기록합니다.")
    @PostMapping("/{commentaryId}/reject")
    fun reject(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Parameter(description = "검수 또는 변경할 장별 해설 UUID.") @PathVariable commentaryId: UUID,
        @Valid @RequestBody request: RejectBibleCommentaryRequest,
    ): ResponseEntity<Void>

    @Operation(summary = "해설 근거 목록 조회", description = "성경 장에 연결된 해설 근거 자료를 조회합니다.")
    @GetMapping("/evidence")
    fun evidence(
        @Parameter(description = "성경 번역본 코드(예: KOR1910).") @RequestParam version: String,
        @Parameter(description = "성경 권 코드(예: GEN).") @RequestParam bookCode: String,
        @Parameter(description = "성경 장 번호(1부터 시작).") @RequestParam chapter: Int,
    ): List<BibleCommentaryEvidenceResponse>

    @Operation(summary = "해설 근거 등록", description = "관리자가 장별 해설에 사용할 근거 자료를 등록합니다.")
    @PostMapping("/evidence")
    fun createEvidence(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Valid @RequestBody request: CreateBibleCommentaryEvidenceRequest,
    ): ResponseEntity<BibleCommentaryEvidenceResponse>

    @Operation(summary = "해설 근거 승인", description = "관리자가 등록된 해설 근거를 승인합니다.")
    @PostMapping("/evidence/{evidenceId}/approve")
    fun approveEvidence(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Parameter(description = "변경할 해설 근거 UUID.") @PathVariable evidenceId: UUID,
    ): BibleCommentaryEvidenceResponse

    @Operation(summary = "해설 근거 사용 중지", description = "관리자가 더 이상 사용할 수 없는 해설 근거를 중지합니다.")
    @PostMapping("/evidence/{evidenceId}/retire")
    fun retireEvidence(@Parameter(description = "변경할 해설 근거 UUID.") @PathVariable evidenceId: UUID): BibleCommentaryEvidenceResponse
}
