package com.example.mypasture.moderation.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.moderation.domain.ModerationAction
import com.example.mypasture.moderation.domain.ModerationReportStatus
import com.example.mypasture.moderation.domain.ModerationTargetType
import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
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
@Tag(name = "관리자 · 신고 검토", description = "신고 내역과 검토 결과를 관리합니다.")
interface ModerationAdminApi {
    @Operation(summary = "신고 목록 조회", description = "관리자가 상태와 대상 유형으로 신고 목록을 페이지로 조회합니다.")
    @GetMapping
    fun list(
        @Parameter(description = "결과를 제한할 처리 상태.") @RequestParam status: ModerationReportStatus?,
        @Parameter(description = "결과를 제한할 신고 대상 종류.") @RequestParam targetType: ModerationTargetType?,
        @Parameter(description = "0부터 시작하는 페이지 번호.") @RequestParam(defaultValue = "0") page: Int,
        @Parameter(description = "한 번에 반환할 최대 항목 수.") @RequestParam(defaultValue = "20") size: Int,
    ): ModerationReportPageResponse

    @Operation(summary = "신고 검토 결과 저장", description = "관리자가 신고를 검토하고 처리 상태와 사유를 기록합니다.")
    @PatchMapping("/{reportId}")
    fun review(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Parameter(description = "검토할 신고 UUID.") @PathVariable reportId: UUID,
        @Valid @RequestBody request: ReviewModerationReportRequest,
    ): ModerationReportResponse
}
