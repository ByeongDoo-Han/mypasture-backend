package com.example.mypasture.bible.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.bible.application.BibleCommentaryService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** 관리자의 장별 해설 생성 요청, 검수 목록, 게시와 거절 요청을 application service에 전달합니다. */
@RestController
@RequestMapping("/api/v1/admin/bible-commentaries")
class BibleCommentaryAdminController(
    private val commentaryService: BibleCommentaryService,
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

    override fun publish(
        principal: JwtPrincipal,
        commentaryId: UUID,
        request: PublishBibleCommentaryRequest,
    ): BibleCommentaryAdminResponse =
        commentaryService.publish(commentaryId, principal.userId, request.qualityScore).toAdminResponse()

    override fun reject(principal: JwtPrincipal, commentaryId: UUID): ResponseEntity<Void> {
        commentaryService.reject(commentaryId, principal.userId)
        return ResponseEntity.noContent().build()
    }
}
