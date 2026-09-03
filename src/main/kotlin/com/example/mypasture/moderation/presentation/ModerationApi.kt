package com.example.mypasture.moderation.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.moderation.domain.ModerationReportReason
import com.example.mypasture.moderation.domain.ModerationTargetType
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
interface ModerationApi {
    @PostMapping("/reports")
    @ResponseStatus(HttpStatus.CREATED)
    fun report(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Valid @RequestBody request: SubmitModerationReportRequest,
    ): ModerationReportReceiptResponse

    @GetMapping("/blocks")
    fun blockedUsers(@AuthenticationPrincipal principal: JwtPrincipal): List<BlockedUserResponse>

    @PutMapping("/blocks/{blockedUserId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun block(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @PathVariable blockedUserId: UUID,
    )

    @DeleteMapping("/blocks/{blockedUserId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun unblock(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @PathVariable blockedUserId: UUID,
    )
}
