package com.example.mypasture.quiet_time.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.quiet_time.domain.QuietTimeSessionScope
import com.example.mypasture.quiet_time.domain.QuietTimeSessionStatus
import jakarta.validation.Valid
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import java.time.LocalDate
import java.util.UUID

/** Swagger 문서와 구현을 분리한 QT 운영 관리자 API contract입니다. */
interface QuietTimeAdminApi {
    @GetMapping("/sessions")
    fun sessions(
        @RequestParam(required = false) scope: QuietTimeSessionScope?,
        @RequestParam(required = false) status: QuietTimeSessionStatus?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(required = false) search: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): QuietTimeAdminSessionPageResponse

    @GetMapping("/sessions/{sessionId}")
    fun session(@PathVariable sessionId: UUID): QuietTimeAdminSessionDetailResponse

    @GetMapping("/statistics")
    fun statistics(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): QuietTimeAdminStatisticsResponse

    @PostMapping("/sessions/{sessionId}/close")
    fun forceClose(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @PathVariable sessionId: UUID,
        @Valid @RequestBody request: QuietTimeAdminActionRequest,
    ): QuietTimeAdminSessionDetailResponse

    @PostMapping("/sessions/{sessionId}/revoke-qr")
    fun revokeQr(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @PathVariable sessionId: UUID,
        @Valid @RequestBody request: QuietTimeAdminActionRequest,
    ): QuietTimeAdminSessionDetailResponse
}
