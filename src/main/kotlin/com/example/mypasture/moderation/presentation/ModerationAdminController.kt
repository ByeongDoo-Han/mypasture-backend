package com.example.mypasture.moderation.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.moderation.application.ModerationReportPage
import com.example.mypasture.moderation.application.ModerationReportView
import com.example.mypasture.moderation.application.ModerationService
import com.example.mypasture.moderation.application.ReviewModerationReportCommand
import com.example.mypasture.moderation.domain.ModerationReportStatus
import com.example.mypasture.moderation.domain.ModerationTargetType
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** 관리자 신고 대기열 조회와 조치 요청을 감사 가능한 service로 전달합니다. */
@RestController
@RequestMapping("/api/v1/admin/moderation/reports")
class ModerationAdminController(
    private val service: ModerationService,
) : ModerationAdminApi {
    override fun list(
        status: ModerationReportStatus?,
        targetType: ModerationTargetType?,
        page: Int,
        size: Int,
    ) = service.adminList(status, targetType, page, size).toResponse()

    override fun review(
        principal: JwtPrincipal,
        reportId: UUID,
        request: ReviewModerationReportRequest,
    ) = service.review(
        reportId,
        principal.userId,
        ReviewModerationReportCommand(request.operationId, request.status, request.action, request.note),
    ).toResponse()
}

private fun ModerationReportPage.toResponse() =
    ModerationReportPageResponse(items.map { it.toResponse() }, page, size, totalElements, totalPages)

private fun ModerationReportView.toResponse() = ModerationReportResponse(
    id = id,
    reporterUserId = reporterUserId,
    targetType = targetType.name,
    targetId = targetId,
    targetUserId = targetUserId,
    reason = reason.name,
    details = details,
    targetSnapshot = targetSnapshot,
    status = status.name,
    action = action.name,
    resolvedBy = resolvedBy,
    resolutionNote = resolutionNote,
    resolvedAt = resolvedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
