package com.example.mypasture.bible.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
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
interface BibleCommentaryAdminApi {
    @GetMapping
    fun commentaries(): List<BibleCommentaryAdminResponse>

    @GetMapping("/generation-jobs")
    fun generationJobs(): List<BibleCommentaryGenerationJobResponse>

    @PostMapping("/generation-jobs")
    fun queueGeneration(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Valid @RequestBody request: QueueBibleCommentaryGenerationRequest,
    ): ResponseEntity<BibleCommentaryGenerationJobResponse>

    @PostMapping("/generation-jobs/books")
    fun queueBookGeneration(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Valid @RequestBody request: QueueBibleCommentaryBookGenerationRequest,
    ): ResponseEntity<BibleCommentaryBookGenerationResponse>

    @PostMapping("/generation-jobs/ranges")
    fun queueRangeGeneration(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Valid @RequestBody request: QueueBibleCommentaryRangeGenerationRequest,
    ): ResponseEntity<BibleCommentaryRangeGenerationResponse>

    @GetMapping("/coverage")
    fun coverage(
        @RequestParam version: String,
        @RequestParam bookCode: String,
    ): BibleCommentaryBookCoverageResponse

    @PutMapping("/{commentaryId}")
    fun edit(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @PathVariable commentaryId: UUID,
        @Valid @RequestBody request: EditBibleCommentaryRequest,
    ): BibleCommentaryAdminResponse

    @GetMapping("/{commentaryId}/reviews")
    fun reviews(@PathVariable commentaryId: UUID): List<BibleCommentaryReviewEventResponse>

    @PostMapping("/{commentaryId}/publish")
    fun publish(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @PathVariable commentaryId: UUID,
        @Valid @RequestBody request: PublishBibleCommentaryRequest,
    ): BibleCommentaryAdminResponse

    @PostMapping("/{commentaryId}/reject")
    fun reject(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @PathVariable commentaryId: UUID,
        @Valid @RequestBody request: RejectBibleCommentaryRequest,
    ): ResponseEntity<Void>

    @GetMapping("/evidence")
    fun evidence(
        @RequestParam version: String,
        @RequestParam bookCode: String,
        @RequestParam chapter: Int,
    ): List<BibleCommentaryEvidenceResponse>

    @PostMapping("/evidence")
    fun createEvidence(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Valid @RequestBody request: CreateBibleCommentaryEvidenceRequest,
    ): ResponseEntity<BibleCommentaryEvidenceResponse>

    @PostMapping("/evidence/{evidenceId}/approve")
    fun approveEvidence(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @PathVariable evidenceId: UUID,
    ): BibleCommentaryEvidenceResponse

    @PostMapping("/evidence/{evidenceId}/retire")
    fun retireEvidence(@PathVariable evidenceId: UUID): BibleCommentaryEvidenceResponse
}
