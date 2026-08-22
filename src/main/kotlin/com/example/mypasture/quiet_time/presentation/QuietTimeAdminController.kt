package com.example.mypasture.quiet_time.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.quiet_time.application.QuietTimeAdminService
import com.example.mypasture.quiet_time.domain.QuietTimeSessionScope
import com.example.mypasture.quiet_time.domain.QuietTimeSessionStatus
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

/** 관리자 QT 조회와 강제 종료·QR 폐기 요청을 application service에 전달합니다. */
@RestController
@RequestMapping("/api/v1/admin/quiet-time")
class QuietTimeAdminController(
    private val quietTimeAdminService: QuietTimeAdminService,
) : QuietTimeAdminApi {
    override fun sessions(
        scope: QuietTimeSessionScope?,
        status: QuietTimeSessionStatus?,
        from: LocalDate?,
        to: LocalDate?,
        search: String?,
        page: Int,
        size: Int,
    ): QuietTimeAdminSessionPageResponse =
        quietTimeAdminService.listSessions(scope, status, from, to, search, page, size).toResponse()

    override fun session(sessionId: UUID): QuietTimeAdminSessionDetailResponse =
        quietTimeAdminService.getSession(sessionId).toResponse()

    override fun statistics(from: LocalDate?, to: LocalDate?): QuietTimeAdminStatisticsResponse =
        quietTimeAdminService.getStatistics(from, to).toResponse()

    override fun forceClose(
        principal: JwtPrincipal,
        sessionId: UUID,
        request: QuietTimeAdminActionRequest,
    ): QuietTimeAdminSessionDetailResponse =
        quietTimeAdminService.forceClose(principal.userId, sessionId, request.operationId, request.reason).toResponse()

    override fun revokeQr(
        principal: JwtPrincipal,
        sessionId: UUID,
        request: QuietTimeAdminActionRequest,
    ): QuietTimeAdminSessionDetailResponse =
        quietTimeAdminService.revokeQr(principal.userId, sessionId, request.operationId, request.reason).toResponse()
}
