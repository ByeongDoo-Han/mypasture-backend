package com.example.mypasture.bible.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
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
    ): ResponseEntity<Void>
}
