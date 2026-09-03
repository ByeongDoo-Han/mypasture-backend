package com.example.mypasture.moderation.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.moderation.application.BlockedUserView
import com.example.mypasture.moderation.application.ModerationReportReceipt
import com.example.mypasture.moderation.application.ModerationService
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** 인증 사용자의 콘텐츠 신고와 개인 차단 요청을 application service에 전달합니다. */
@RestController
@RequestMapping("/api/v1/moderation")
class ModerationController(
    private val service: ModerationService,
) : ModerationApi {
    override fun report(principal: JwtPrincipal, request: SubmitModerationReportRequest) =
        service.submitReport(principal.userId, request.targetType, request.targetId, request.reason, request.details).toResponse()

    override fun blockedUsers(principal: JwtPrincipal) = service.blockedUsers(principal.userId).map { it.toResponse() }

    override fun block(principal: JwtPrincipal, blockedUserId: UUID) = service.block(principal.userId, blockedUserId)

    override fun unblock(principal: JwtPrincipal, blockedUserId: UUID) = service.unblock(principal.userId, blockedUserId)
}

private fun ModerationReportReceipt.toResponse() =
    ModerationReportReceiptResponse(id, targetType.name, targetId, status.name, updatedAt)

private fun BlockedUserView.toResponse() = BlockedUserResponse(userId, displayName, blockedAt)
