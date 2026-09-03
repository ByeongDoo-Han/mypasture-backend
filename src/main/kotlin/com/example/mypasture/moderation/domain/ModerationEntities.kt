package com.example.mypasture.moderation.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

/** 신고할 수 있는 현재 서비스 콘텐츠 종류를 안정적인 API 및 DB 값으로 표현합니다. */
enum class ModerationTargetType { AI_ANSWER, QUIET_TIME_ANSWER }

/** 신고자가 선택하는 사유를 운영 정책의 분류 기준으로 표현합니다. */
enum class ModerationReportReason {
    OFFENSIVE,
    HARASSMENT,
    HATE_OR_DISCRIMINATION,
    SEXUAL_CONTENT,
    SELF_HARM_OR_DANGEROUS,
    PRIVACY,
    FALSE_INFORMATION,
    OTHER,
}

/** 운영 검토 대기열의 처리 상태입니다. */
enum class ModerationReportStatus { OPEN, IN_REVIEW, RESOLVED, DISMISSED }

/** 검토 결과 콘텐츠 노출에 적용할 조치입니다. */
enum class ModerationAction { NONE, CONTENT_HIDDEN }

/** 사용자 신고의 대상 스냅샷과 운영 처리 결과를 암호화해 보존합니다. */
@Entity
@Table(
    name = "moderation_reports",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_moderation_reporter_target",
            columnNames = ["reporter_user_id", "target_type", "target_id"],
        ),
    ],
)
class ModerationReportEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "reporter_user_id", nullable = false)
    val reporterUserId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 40)
    val targetType: ModerationTargetType,
    @Column(name = "target_id", nullable = false)
    val targetId: UUID,
    @Column(name = "target_user_id")
    var targetUserId: UUID? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    var reason: ModerationReportReason,
    @Column(name = "details_ciphertext", columnDefinition = "text")
    var encryptedDetails: String? = null,
    @Column(name = "target_snapshot_ciphertext", nullable = false, columnDefinition = "text")
    var encryptedTargetSnapshot: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: ModerationReportStatus = ModerationReportStatus.OPEN,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    var action: ModerationAction = ModerationAction.NONE,
    @Column(name = "resolved_by")
    var resolvedBy: UUID? = null,
    @Column(name = "resolution_note_ciphertext", columnDefinition = "text")
    var encryptedResolutionNote: String? = null,
    @Column(name = "resolved_at")
    var resolvedAt: Instant? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt,
) {
    fun reopen(
        targetUserId: UUID?,
        reason: ModerationReportReason,
        encryptedDetails: String?,
        encryptedTargetSnapshot: String,
        now: Instant,
    ) {
        this.targetUserId = targetUserId
        this.reason = reason
        this.encryptedDetails = encryptedDetails
        this.encryptedTargetSnapshot = encryptedTargetSnapshot
        status = ModerationReportStatus.OPEN
        if (action != ModerationAction.CONTENT_HIDDEN) action = ModerationAction.NONE
        resolvedBy = null
        encryptedResolutionNote = null
        resolvedAt = null
        updatedAt = now
    }

    fun review(
        status: ModerationReportStatus,
        action: ModerationAction,
        adminUserId: UUID,
        encryptedResolutionNote: String,
        now: Instant,
    ) {
        this.status = status
        this.action = action
        resolvedBy = adminUserId
        this.encryptedResolutionNote = encryptedResolutionNote
        resolvedAt = now
        updatedAt = now
    }
}

/** 한 사용자가 다른 사용자의 콘텐츠를 보지 않도록 하는 개인 차단 관계입니다. */
@Entity
@Table(
    name = "user_blocks",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_user_blocks_pair", columnNames = ["blocker_user_id", "blocked_user_id"]),
    ],
)
class UserBlockEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "blocker_user_id", nullable = false)
    val blockerUserId: UUID,
    @Column(name = "blocked_user_id", nullable = false)
    val blockedUserId: UUID,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
