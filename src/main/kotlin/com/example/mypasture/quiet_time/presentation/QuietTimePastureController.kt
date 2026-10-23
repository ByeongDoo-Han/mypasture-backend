package com.example.mypasture.quiet_time.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.quiet_time.application.QuietTimePastureMembershipService
import com.example.mypasture.quiet_time.domain.QuietTimePastureEntity
import java.util.UUID
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 고정 목장 소속 초대와 목자 승인 API를 제공합니다. */
@RestController
@RequestMapping("/api/v1/quiet-time/pastures")
class QuietTimePastureController(private val service: QuietTimePastureMembershipService) : QuietTimePastureApi {
    override fun current(principal: JwtPrincipal): QuietTimePastureCurrentResponse = service.state(principal.userId).let {
        QuietTimePastureCurrentResponse(it.status, it.pasture?.toSummary(), it.requestId, it.role?.name)
    }

    override fun issueInvite(principal: JwtPrincipal, pastureId: UUID): QuietTimePastureInviteResponse =
        service.issueInvite(principal.userId, pastureId).let { QuietTimePastureInviteResponse(it.code, it.inviteUrl, it.expiresAt) }

    override fun resolveInvite(principal: JwtPrincipal, request: ResolvePastureInviteRequest): QuietTimePastureSummaryResponse =
        service.resolveInvite(request.code).toSummary()

    override fun requestJoin(principal: JwtPrincipal, request: RequestPastureJoinRequest): QuietTimePastureJoinRequestResponse =
        service.requestJoin(principal.userId, request.code, request.realName).let {
            QuietTimePastureJoinRequestResponse(it.id, it.pasture.toSummary(), it.status.name)
        }

    override fun pendingRequests(principal: JwtPrincipal, pastureId: UUID): List<QuietTimePasturePendingRequestResponse> =
        service.pendingRequests(principal.userId, pastureId).map {
            QuietTimePasturePendingRequestResponse(it.id, requireNotNull(it.realName), it.createdAt, it.expiresAt)
        }

    override fun approve(principal: JwtPrincipal, pastureId: UUID, requestId: UUID): ResponseEntity<Void> {
        service.decide(principal.userId, pastureId, requestId, true)
        return ResponseEntity.noContent().build()
    }

    override fun reject(principal: JwtPrincipal, pastureId: UUID, requestId: UUID): ResponseEntity<Void> {
        service.decide(principal.userId, pastureId, requestId, false)
        return ResponseEntity.noContent().build()
    }
}

private fun QuietTimePastureEntity.toSummary() = QuietTimePastureSummaryResponse(id, name, leaderName)
