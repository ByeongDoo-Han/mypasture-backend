package com.example.mypasture.moderation.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.moderation.domain.ModerationAction
import com.example.mypasture.moderation.domain.ModerationReportStatus
import com.example.mypasture.moderation.domain.ModerationTargetType
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import java.time.Instant
import java.util.UUID

/** 관리자의 멱등 처리 ID, 판정과 내부 처리 메모를 전달합니다. */
data class ReviewModerationReportRequest(
    val operationId: UUID,
    val status: ModerationReportStatus,
    val action: ModerationAction,
    @field:Size(min = 5, max = 500)
    val note: String,
)

/** 권한 있는 운영자에게만 복호화해 제공하는 신고 검토 문맥입니다. */
data class ModerationReportResponse(
    val id: UUID,
    val reporterUserId: UUID,
    val targetType: String,
    val targetId: UUID,
    val targetUserId: UUID?,
    val reason: String,
    val details: String?,
    val targetSnapshot: String,
    val status: String,
    val action: String,
    val resolvedBy: UUID?,
    val resolutionNote: String?,
    val resolvedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** 신고 대기열의 페이지 응답입니다. */
data class ModerationReportPageResponse(
    val items: List<ModerationReportResponse>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)

/** Swagger 문서와 controller 구현을 분리한 관리자 신고 검토 API 계약입니다. */
interface ModerationAdminApi {
    @GetMapping
    fun list(
        @RequestParam status: ModerationReportStatus?,
        @RequestParam targetType: ModerationTargetType?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ModerationReportPageResponse

    @PatchMapping("/{reportId}")
    fun review(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @PathVariable reportId: UUID,
        @Valid @RequestBody request: ReviewModerationReportRequest,
    ): ModerationReportResponse
}
