package com.example.mypasture.quiet_time.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.quiet_time.domain.QuietTimeSessionScope
import com.example.mypasture.quiet_time.domain.QuietTimeSessionStatus
import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
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
@Tag(name = "관리자 · QT 모임", description = "QT 모임 운영 현황과 관리자 조치를 처리합니다.")
interface QuietTimeAdminApi {
    @Operation(summary = "QT 모임 목록 조회", description = "관리자가 기간·상태 등 조건으로 QT 모임 목록을 조회합니다.")
    @GetMapping("/sessions")
    fun sessions(
        @Parameter(description = "조회할 QT 모임의 운영 범위.") @RequestParam(required = false) scope: QuietTimeSessionScope?,
        @Parameter(description = "결과를 제한할 처리 상태.") @RequestParam(required = false) status: QuietTimeSessionStatus?,
        @Parameter(description = "조회 범위 시작 날짜(YYYY-MM-DD)(포함).") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @Parameter(description = "조회 범위 종료 날짜(YYYY-MM-DD)(포함).") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @Parameter(description = "모임 목록에서 찾을 검색어.") @RequestParam(required = false) search: String?,
        @Parameter(description = "0부터 시작하는 페이지 번호.") @RequestParam(defaultValue = "0") page: Int,
        @Parameter(description = "한 번에 반환할 최대 항목 수.") @RequestParam(defaultValue = "20") size: Int,
    ): QuietTimeAdminSessionPageResponse

    @Operation(summary = "QT 모임 상세 조회", description = "관리자가 선택한 QT 모임의 운영 상세를 조회합니다.")
    @GetMapping("/sessions/{sessionId}")
    fun session(@Parameter(description = "조회하거나 변경할 QT 모임 UUID.") @PathVariable sessionId: UUID): QuietTimeAdminSessionDetailResponse

    @Operation(summary = "QT 모임 통계", description = "관리자가 지정한 기간의 QT 모임 운영 통계를 조회합니다.")
    @GetMapping("/statistics")
    fun statistics(
        @Parameter(description = "조회 범위 시작 날짜(YYYY-MM-DD)(포함).") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @Parameter(description = "조회 범위 종료 날짜(YYYY-MM-DD)(포함).") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): QuietTimeAdminStatisticsResponse

    @Operation(summary = "QT 모임 강제 종료", description = "관리자가 모임을 종료하고 조치 사유를 기록합니다.")
    @PostMapping("/sessions/{sessionId}/close")
    fun forceClose(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Parameter(description = "조회하거나 변경할 QT 모임 UUID.") @PathVariable sessionId: UUID,
        @Valid @RequestBody request: QuietTimeAdminActionRequest,
    ): QuietTimeAdminSessionDetailResponse

    @Operation(summary = "QT 모임 QR 무효화", description = "관리자가 모임의 기존 QR 초대 코드를 무효화하고 사유를 기록합니다.")
    @PostMapping("/sessions/{sessionId}/revoke-qr")
    fun revokeQr(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Parameter(description = "조회하거나 변경할 QT 모임 UUID.") @PathVariable sessionId: UUID,
        @Valid @RequestBody request: QuietTimeAdminActionRequest,
    ): QuietTimeAdminSessionDetailResponse
}
