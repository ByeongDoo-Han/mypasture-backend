package com.example.mypasture.quiet_time.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody

data class QuietTimePastureCurrentResponse(
    val status: String,
    val pasture: QuietTimePastureSummaryResponse?,
    val requestId: UUID? = null,
    val role: String? = null,
)

data class QuietTimePastureSummaryResponse(val id: UUID, val name: String, val leaderName: String)
data class QuietTimePastureInviteResponse(val code: String, val inviteUrl: String, val expiresAt: Instant)
data class QuietTimePastureJoinRequestResponse(val id: UUID, val pasture: QuietTimePastureSummaryResponse, val status: String)
data class QuietTimePasturePendingRequestResponse(val id: UUID, val realName: String, val createdAt: Instant, val expiresAt: Instant)
data class ResolvePastureInviteRequest(@field:NotBlank val code: String)
data class RequestPastureJoinRequest(
    @field:NotBlank val code: String,
    @field:NotBlank @field:Size(min = 2, max = 80) val realName: String,
)

/** 고정 목장 소속과 일 분짜리 초대의 Swagger 계약입니다. */
@Tag(name = "고정 목장", description = "단일 소속, 임시 초대와 목자의 가입 승인을 처리합니다.")
interface QuietTimePastureApi {
    @Operation(summary = "내 고정 목장 상태", description = "미소속·승인 대기·목원·목자 상태를 조회합니다.")
    @GetMapping("/current")
    fun current(@AuthenticationPrincipal principal: JwtPrincipal): QuietTimePastureCurrentResponse

    @Operation(summary = "목장 초대 코드 발급", description = "해당 목장의 목원 또는 목자가 1분 유효한 문자·QR 초대를 발급합니다.")
    @PostMapping("/{pastureId}/invites")
    fun issueInvite(@AuthenticationPrincipal principal: JwtPrincipal, @Parameter(description = "초대를 발급할 고정 목장 UUID.") @PathVariable pastureId: UUID): QuietTimePastureInviteResponse

    @Operation(summary = "목장 초대 코드 확인", description = "유효한 초대 코드에 해당하는 목장 이름과 목자를 조회합니다.")
    @PostMapping("/invites/resolve")
    fun resolveInvite(@AuthenticationPrincipal principal: JwtPrincipal, @Valid @RequestBody request: ResolvePastureInviteRequest): QuietTimePastureSummaryResponse

    @Operation(summary = "목장 가입 요청", description = "본명을 임시로 제출해 목자 승인을 요청합니다. 7일 내 처리되지 않으면 본명을 삭제합니다.")
    @PostMapping("/join-requests")
    fun requestJoin(@AuthenticationPrincipal principal: JwtPrincipal, @Valid @RequestBody request: RequestPastureJoinRequest): QuietTimePastureJoinRequestResponse

    @Operation(summary = "목장 가입 요청 목록", description = "목자만 아직 유효한 가입 요청과 심사용 본명을 조회합니다.")
    @GetMapping("/{pastureId}/join-requests")
    fun pendingRequests(@AuthenticationPrincipal principal: JwtPrincipal, @Parameter(description = "가입 요청을 조회할 고정 목장 UUID.") @PathVariable pastureId: UUID): List<QuietTimePasturePendingRequestResponse>

    @Operation(summary = "목장 가입 승인", description = "목자가 요청을 승인하고 본명을 즉시 지웁니다.")
    @PostMapping("/{pastureId}/join-requests/{requestId}/approve")
    fun approve(@AuthenticationPrincipal principal: JwtPrincipal, @Parameter(description = "가입 요청 대상 고정 목장 UUID.") @PathVariable pastureId: UUID, @Parameter(description = "승인할 가입 요청 UUID.") @PathVariable requestId: UUID): ResponseEntity<Void>

    @Operation(summary = "목장 가입 거절", description = "목자가 요청을 거절하고 본명을 즉시 지웁니다.")
    @PostMapping("/{pastureId}/join-requests/{requestId}/reject")
    fun reject(@AuthenticationPrincipal principal: JwtPrincipal, @Parameter(description = "가입 요청 대상 고정 목장 UUID.") @PathVariable pastureId: UUID, @Parameter(description = "거절할 가입 요청 UUID.") @PathVariable requestId: UUID): ResponseEntity<Void>
}
