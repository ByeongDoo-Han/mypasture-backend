package com.example.mypasture.moderation.application

import com.example.mypasture.moderation.domain.ModerationAction
import com.example.mypasture.moderation.domain.ModerationReportReason
import com.example.mypasture.moderation.domain.ModerationReportStatus
import com.example.mypasture.moderation.domain.ModerationTargetType
import java.time.Instant
import java.util.UUID

/** 신고 접수 후 앱에 노출할 최소 처리 상태입니다. */
data class ModerationReportReceipt(
    val id: UUID,
    val targetType: ModerationTargetType,
    val targetId: UUID,
    val status: ModerationReportStatus,
    val updatedAt: Instant,
)

/** 사용자가 차단한 계정의 공개 표시 정보입니다. */
data class BlockedUserView(
    val userId: UUID,
    val displayName: String,
    val blockedAt: Instant,
)

/** 운영자 신고 대기열 한 건과 암호화 저장된 검토 문맥입니다. */
data class ModerationReportView(
    val id: UUID,
    val reporterUserId: UUID,
    val targetType: ModerationTargetType,
    val targetId: UUID,
    val targetUserId: UUID?,
    val reason: ModerationReportReason,
    val details: String?,
    val targetSnapshot: String,
    val status: ModerationReportStatus,
    val action: ModerationAction,
    val resolvedBy: UUID?,
    val resolutionNote: String?,
    val resolvedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** application 계층에서 사용하는 신고 대기열 페이지입니다. */
data class ModerationReportPage(
    val items: List<ModerationReportView>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)

/** 관리자 검토 결과를 멱등하게 적용하기 위한 application command입니다. */
data class ReviewModerationReportCommand(
    val operationId: UUID,
    val status: ModerationReportStatus,
    val action: ModerationAction,
    val note: String,
)

/** 정책상 허용되지 않는 신고·차단·검토 입력을 나타냅니다. */
class ModerationInvalidRequestException(message: String) : RuntimeException(message)

/** 신고자의 소유권 또는 조회 권한을 확인할 수 없는 대상입니다. */
class ModerationTargetUnavailableException : RuntimeException("신고할 수 있는 콘텐츠를 찾을 수 없습니다.")

/** 존재하지 않거나 탈퇴한 사용자를 차단하려는 요청입니다. */
class ModerationUserUnavailableException : RuntimeException("차단할 사용자를 찾을 수 없습니다.")

/** 관리자가 요청한 신고 내역이 존재하지 않음을 나타냅니다. */
class ModerationReportNotFoundException : RuntimeException("신고 내역을 찾을 수 없습니다.")

/** 동일 operation ID가 다른 관리자 조치에 재사용된 충돌입니다. */
class ModerationOperationConflictException : RuntimeException("operationId가 다른 신고 처리 요청에 이미 사용되었습니다.")
