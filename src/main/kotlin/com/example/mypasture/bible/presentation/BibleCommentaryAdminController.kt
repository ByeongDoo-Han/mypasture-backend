package com.example.mypasture.bible.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.bible.application.BibleCommentaryService
import com.example.mypasture.bible.application.BibleCommentaryEvidenceService
import com.example.mypasture.bible.application.BibleCommentaryBatchService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** 관리자의 장별 해설 생성 요청, 검수 목록, 게시와 거절 요청을 application service에 전달합니다. */
@RestController
@RequestMapping("/api/v1/admin/bible-commentaries")
class BibleCommentaryAdminController(
    private val commentaryService: BibleCommentaryService,
    private val evidenceService: BibleCommentaryEvidenceService,
    private val batchService: BibleCommentaryBatchService,
) : BibleCommentaryAdminApi {
    override fun commentaries(): List<BibleCommentaryAdminResponse> =
        commentaryService.listCommentaries().map { it.toAdminResponse() }

    override fun generationJobs(): List<BibleCommentaryGenerationJobResponse> =
        commentaryService.listGenerationJobs().map { it.toResponse() }

    override fun queueGeneration(
        principal: JwtPrincipal,
        request: QueueBibleCommentaryGenerationRequest,
    ): ResponseEntity<BibleCommentaryGenerationJobResponse> =
        ResponseEntity.accepted().body(
            commentaryService.queueGeneration(request.version, request.bookCode, request.chapter, principal.userId).toResponse(),
        )

    override fun queueBookGeneration(
        principal: JwtPrincipal,
        request: QueueBibleCommentaryBookGenerationRequest,
    ): ResponseEntity<BibleCommentaryBookGenerationResponse> =
        ResponseEntity.accepted().body(
            batchService.queueBookGeneration(
                request.version,
                request.bookCode,
                request.includePublished,
                principal.userId,
            ).toResponse(),
        )

    override fun edit(
        principal: JwtPrincipal,
        commentaryId: UUID,
        request: EditBibleCommentaryRequest,
    ): BibleCommentaryAdminResponse =
        commentaryService.editDraft(commentaryId, principal.userId, request.toCommand()).toAdminResponse()

    override fun reviews(commentaryId: UUID): List<BibleCommentaryReviewEventResponse> =
        commentaryService.reviewEvents(commentaryId).map { it.toResponse() }

    override fun publish(
        principal: JwtPrincipal,
        commentaryId: UUID,
        request: PublishBibleCommentaryRequest,
    ): BibleCommentaryAdminResponse =
        commentaryService.publish(commentaryId, principal.userId, request.qualityScore, request.reviewNotes).toAdminResponse()

    override fun reject(
        principal: JwtPrincipal,
        commentaryId: UUID,
        request: RejectBibleCommentaryRequest,
    ): ResponseEntity<Void> {
        commentaryService.reject(commentaryId, principal.userId, request.reviewNotes)
        return ResponseEntity.noContent().build()
    }

    override fun evidence(version: String, bookCode: String, chapter: Int): List<BibleCommentaryEvidenceResponse> =
        evidenceService.list(version, bookCode, chapter).map { it.toResponse() }

    override fun createEvidence(
        principal: JwtPrincipal,
        request: CreateBibleCommentaryEvidenceRequest,
    ): ResponseEntity<BibleCommentaryEvidenceResponse> =
        ResponseEntity.status(201).body(evidenceService.create(request.toCommand(), principal.userId).toResponse())

    override fun approveEvidence(principal: JwtPrincipal, evidenceId: UUID): BibleCommentaryEvidenceResponse =
        evidenceService.approve(evidenceId, principal.userId).toResponse()

    override fun retireEvidence(evidenceId: UUID): BibleCommentaryEvidenceResponse =
        evidenceService.retire(evidenceId).toResponse()
}
