package com.example.mypasture.moderation.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.moderation.domain.ModerationReportReason
import com.example.mypasture.moderation.domain.ModerationTargetType
import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import java.time.Instant
import java.util.UUID

/** 사용자가 신고할 대상, 정책 사유와 선택적 상세 문맥을 전달합니다. */
data class SubmitModerationReportRequest(
    val targetType: ModerationTargetType,
    val targetId: UUID,
    val reason: ModerationReportReason,
    @field:Size(max = 500)
    val details: String? = null,
)

/** 신고 접수 ID와 현재 처리 상태만 반환하는 최소 응답입니다. */
data class ModerationReportReceiptResponse(
    val id: UUID,
    val targetType: String,
    val targetId: UUID,
    val status: String,
    val updatedAt: Instant,
)

/** 내 차단 목록에 표시할 사용자 공개 정보와 차단 시각입니다. */
data class BlockedUserResponse(
    val userId: UUID,
    val displayName: String,
    val blockedAt: Instant,
)

/** Swagger 문서와 controller 구현을 분리한 사용자 신고·차단 API 계약입니다. */
@Tag(name = "신고 · 차단", description = "사용자 신고와 개인 차단 목록을 관리합니다.")
interface ModerationApi {
    @Operation(summary = "콘텐츠 또는 사용자 신고", description = "로그인한 사용자가 대상과 사유를 제출해 신고를 생성합니다.")
    @PostMapping("/reports")
    @ResponseStatus(HttpStatus.CREATED)
    fun report(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Valid @RequestBody request: SubmitModerationReportRequest,
    ): ModerationReportReceiptResponse

    @Operation(summary = "내 차단 목록 조회", description = "로그인한 사용자가 차단한 사용자 목록을 반환합니다.")
    @GetMapping("/blocks")
    fun blockedUsers(@AuthenticationPrincipal principal: JwtPrincipal): List<BlockedUserResponse>

    @Operation(summary = "사용자 차단", description = "지정한 사용자를 내 차단 목록에 추가합니다.")
    @PutMapping("/blocks/{blockedUserId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun block(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Parameter(description = "차단하거나 차단 해제할 사용자 UUID.") @PathVariable blockedUserId: UUID,
    )

    @Operation(summary = "사용자 차단 해제", description = "지정한 사용자를 내 차단 목록에서 제거합니다.")
    @DeleteMapping("/blocks/{blockedUserId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun unblock(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Parameter(description = "차단하거나 차단 해제할 사용자 UUID.") @PathVariable blockedUserId: UUID,
    )
}
