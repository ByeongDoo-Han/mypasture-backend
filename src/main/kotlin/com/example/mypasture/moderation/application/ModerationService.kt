package com.example.mypasture.moderation.application

import com.example.mypasture.admin_audit.domain.AdminAuditAction
import com.example.mypasture.admin_audit.domain.AdminAuditLogEntity
import com.example.mypasture.admin_audit.infrastructure.AdminAuditLogRepository
import com.example.mypasture.ai.domain.AiRequestReceiptStatus
import com.example.mypasture.ai.domain.AiUsageStatus
import com.example.mypasture.ai.infrastructure.AiRequestReceiptRepository
import com.example.mypasture.ai.infrastructure.AiRequestResponseCodec
import com.example.mypasture.ai.infrastructure.AiUsageLogRepository
import com.example.mypasture.auth.infrastructure.UserRepository
import com.example.mypasture.moderation.domain.ModerationAction
import com.example.mypasture.moderation.domain.ModerationReportEntity
import com.example.mypasture.moderation.domain.ModerationReportReason
import com.example.mypasture.moderation.domain.ModerationReportStatus
import com.example.mypasture.moderation.domain.ModerationTargetType
import com.example.mypasture.moderation.domain.UserBlockEntity
import com.example.mypasture.moderation.infrastructure.ModerationCipher
import com.example.mypasture.moderation.infrastructure.ModerationReportRepository
import com.example.mypasture.moderation.infrastructure.UserBlockRepository
import com.example.mypasture.quiet_time.infrastructure.QuietTimeParticipantRepository
import com.example.mypasture.quiet_time.infrastructure.QuietTimeQuestionAnswerRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/** 신고 대상 소유권을 검증하고 민감한 대상 문맥을 암호화해 운영 대기열에 접수합니다. */
@Service
class ModerationService(
    private val reports: ModerationReportRepository,
    private val blocks: UserBlockRepository,
    private val users: UserRepository,
    private val aiUsageLogs: AiUsageLogRepository,
    private val aiReceipts: AiRequestReceiptRepository,
    private val aiResponseCodec: AiRequestResponseCodec,
    private val quietTimeAnswers: QuietTimeQuestionAnswerRepository,
    private val quietTimeParticipants: QuietTimeParticipantRepository,
    private val cipher: ModerationCipher,
    private val audits: AdminAuditLogRepository,
) {
    @Transactional
    fun submitReport(
        reporterUserId: UUID,
        targetType: ModerationTargetType,
        targetId: UUID,
        reason: ModerationReportReason,
        details: String?,
        now: Instant = Instant.now(),
    ): ModerationReportReceipt {
        users.findActiveByIdForUpdate(reporterUserId) ?: throw ModerationUserUnavailableException()
        val normalizedDetails = details?.trim()?.takeIf(String::isNotBlank)
        if (normalizedDetails != null && normalizedDetails.length > MAX_DETAILS_LENGTH) {
            throw ModerationInvalidRequestException("신고 상세 내용은 500자 이하여야 합니다.")
        }
        val target = resolveTarget(reporterUserId, targetType, targetId)
        val encryptedDetails = normalizedDetails?.let(cipher::encrypt)
        val encryptedSnapshot = cipher.encrypt(target.snapshot.take(MAX_SNAPSHOT_LENGTH))
        val existing = reports.findLockedByReporterUserIdAndTargetTypeAndTargetId(reporterUserId, targetType, targetId)
        val report = existing?.also {
            it.reopen(target.targetUserId, reason, encryptedDetails, encryptedSnapshot, now)
        } ?: ModerationReportEntity(
                reporterUserId = reporterUserId,
                targetType = targetType,
                targetId = targetId,
                targetUserId = target.targetUserId,
                reason = reason,
                encryptedDetails = encryptedDetails,
                encryptedTargetSnapshot = encryptedSnapshot,
                createdAt = now,
                updatedAt = now,
            )
        return reports.save(report).toReceipt()
    }

    @Transactional
    fun block(blockerUserId: UUID, blockedUserId: UUID, now: Instant = Instant.now()) {
        if (blockerUserId == blockedUserId) throw ModerationInvalidRequestException("자기 자신은 차단할 수 없습니다.")
        users.findActiveByIdForUpdate(blockerUserId) ?: throw ModerationUserUnavailableException()
        users.findByIdAndDeletedAtIsNull(blockedUserId) ?: throw ModerationUserUnavailableException()
        if (blocks.findByBlockerUserIdAndBlockedUserId(blockerUserId, blockedUserId) == null) {
            blocks.save(UserBlockEntity(blockerUserId = blockerUserId, blockedUserId = blockedUserId, createdAt = now))
        }
    }

    @Transactional
    fun unblock(blockerUserId: UUID, blockedUserId: UUID) {
        blocks.findByBlockerUserIdAndBlockedUserId(blockerUserId, blockedUserId)?.let(blocks::delete)
    }

    @Transactional(readOnly = true)
    fun blockedUsers(blockerUserId: UUID): List<BlockedUserView> {
        val relationships = blocks.findAllByBlockerUserIdOrderByCreatedAtDesc(blockerUserId)
        val names = users.findAllById(relationships.map { it.blockedUserId }).associate { it.id to it.displayName }
        return relationships.map { block ->
            BlockedUserView(block.blockedUserId, names[block.blockedUserId] ?: "탈퇴한 사용자", block.createdAt)
        }
    }

    @Transactional(readOnly = true)
    fun adminList(
        status: ModerationReportStatus?,
        targetType: ModerationTargetType?,
        page: Int,
        size: Int,
    ): ModerationReportPage {
        val pageable = PageRequest.of(
            page.coerceAtLeast(0),
            size.coerceIn(1, 100),
            Sort.by(Sort.Direction.DESC, "createdAt"),
        )
        val result = reports.findAdminPage(status, targetType, pageable)
        return ModerationReportPage(
            items = result.content.map(::toAdminView),
            page = result.number,
            size = result.size,
            totalElements = result.totalElements,
            totalPages = result.totalPages,
        )
    }

    @Transactional
    fun review(
        reportId: UUID,
        adminUserId: UUID,
        command: ReviewModerationReportCommand,
        now: Instant = Instant.now(),
    ): ModerationReportView {
        val normalizedNote = command.note.trim()
        val requestFingerprint = fingerprint(reportId, command.status, command.action, normalizedNote)
        audits.findByOperationId(command.operationId)?.let { audit ->
            if (
                audit.action != AdminAuditAction.MODERATION_REPORT_REVIEWED ||
                audit.targetType != MODERATION_AUDIT_TARGET ||
                audit.targetId != reportId ||
                audit.requestFingerprint != requestFingerprint
            ) {
                throw ModerationOperationConflictException()
            }
            return reports.findById(reportId).orElseThrow { ModerationReportNotFoundException() }.let(::toAdminView)
        }
        validateReview(command)
        val report = reports.findByIdForUpdate(reportId) ?: throw ModerationReportNotFoundException()
        report.review(command.status, command.action, adminUserId, cipher.encrypt(normalizedNote), now)
        audits.save(
            AdminAuditLogEntity(
                operationId = command.operationId,
                adminUserId = adminUserId,
                action = AdminAuditAction.MODERATION_REPORT_REVIEWED,
                targetType = MODERATION_AUDIT_TARGET,
                targetId = report.id,
                reason = "신고 처리 상태 변경",
                details = "status=${command.status},action=${command.action},targetType=${report.targetType}",
                requestFingerprint = requestFingerprint,
            ),
        )
        return toAdminView(report)
    }

    private fun resolveTarget(reporterUserId: UUID, targetType: ModerationTargetType, targetId: UUID): ResolvedTarget =
        when (targetType) {
            ModerationTargetType.AI_ANSWER -> resolveAiAnswer(reporterUserId, targetId)
            ModerationTargetType.QUIET_TIME_ANSWER -> resolveQuietTimeAnswer(reporterUserId, targetId)
        }

    private fun resolveAiAnswer(reporterUserId: UUID, targetId: UUID): ResolvedTarget {
        val usage = aiUsageLogs.findByIdAndUserIdAndStatus(targetId, reporterUserId, AiUsageStatus.COMPLETED)
            ?: throw ModerationTargetUnavailableException()
        val answer = aiReceipts.findByUsageLogId(targetId)
            ?.takeIf { it.status == AiRequestReceiptStatus.COMPLETED }
            ?.encryptedResponse
            ?.let { runCatching { aiResponseCodec.decode(it).answer }.getOrNull() }
        val snapshot = buildString {
            append("AI answer turnId=").append(targetId).append('\n')
            append("model=").append(usage.model).append('\n')
            append("questionIntent=").append(usage.questionIntent).append('\n')
            append("answer=").append(answer ?: "응답 보관 기간이 지나 본문을 복원할 수 없습니다.")
        }
        return ResolvedTarget(targetUserId = null, snapshot = snapshot)
    }

    private fun resolveQuietTimeAnswer(reporterUserId: UUID, targetId: UUID): ResolvedTarget {
        val answer = quietTimeAnswers.findById(targetId).orElseThrow { ModerationTargetUnavailableException() }
        val session = answer.session
        val canRead = session.isManagedBy(reporterUserId) || quietTimeParticipants.existsBySessionIdAndUserId(session.id, reporterUserId)
        if (!canRead) throw ModerationTargetUnavailableException()
        if (answer.userId == reporterUserId) throw ModerationInvalidRequestException("자신의 답변은 신고할 수 없습니다.")
        return ResolvedTarget(
            targetUserId = answer.userId,
            snapshot = "작성자=${answer.authorDisplayName}\n질문=${answer.questionText}\n답변=${answer.content}",
        )
    }

    private fun validateReview(command: ReviewModerationReportCommand) {
        if (command.status !in setOf(ModerationReportStatus.RESOLVED, ModerationReportStatus.DISMISSED)) {
            throw ModerationInvalidRequestException("운영 검토 결과는 RESOLVED 또는 DISMISSED여야 합니다.")
        }
        if (command.status == ModerationReportStatus.DISMISSED && command.action != ModerationAction.NONE) {
            throw ModerationInvalidRequestException("기각한 신고에는 콘텐츠 숨김을 적용할 수 없습니다.")
        }
        if (command.note.trim().length !in 5..500) {
            throw ModerationInvalidRequestException("처리 사유는 5자 이상 500자 이하로 입력해 주세요.")
        }
    }

    private fun toAdminView(report: ModerationReportEntity) = ModerationReportView(
        id = report.id,
        reporterUserId = report.reporterUserId,
        targetType = report.targetType,
        targetId = report.targetId,
        targetUserId = report.targetUserId,
        reason = report.reason,
        details = report.encryptedDetails?.let { runCatching { cipher.decrypt(it) }.getOrNull() },
        targetSnapshot = runCatching { cipher.decrypt(report.encryptedTargetSnapshot) }.getOrDefault("대상 내용을 복호화할 수 없습니다."),
        status = report.status,
        action = report.action,
        resolvedBy = report.resolvedBy,
        resolutionNote = report.encryptedResolutionNote?.let { runCatching { cipher.decrypt(it) }.getOrNull() },
        resolvedAt = report.resolvedAt,
        createdAt = report.createdAt,
        updatedAt = report.updatedAt,
    )

    private fun ModerationReportEntity.toReceipt() = ModerationReportReceipt(id, targetType, targetId, status, updatedAt)

    private fun fingerprint(
        reportId: UUID,
        status: ModerationReportStatus,
        action: ModerationAction,
        note: String,
    ): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256")
            .digest("$reportId|$status|$action|$note".toByteArray(StandardCharsets.UTF_8)),
    )

    private data class ResolvedTarget(val targetUserId: UUID?, val snapshot: String)

    companion object {
        private const val MAX_DETAILS_LENGTH = 500
        private const val MAX_SNAPSHOT_LENGTH = 12_000
        private const val MODERATION_AUDIT_TARGET = "MODERATION_REPORT"
    }
}

/** 차단 관계와 운영 숨김 조치를 QT 조회 및 실시간 전달에 적용합니다. */
@Service
class ModerationVisibilityService(
    private val reports: ModerationReportRepository,
    private val blocks: UserBlockRepository,
) {
    @Transactional(readOnly = true)
    fun hiddenTargetIds(targetType: ModerationTargetType, targetIds: Collection<UUID>): Set<UUID> =
        if (targetIds.isEmpty()) emptySet()
        else reports.findTargetIdsByAction(targetType, targetIds, ModerationAction.CONTENT_HIDDEN)

    @Transactional(readOnly = true)
    fun blockedUserIds(blockerUserId: UUID): Set<UUID> = blocks.findBlockedUserIds(blockerUserId)

    @Transactional(readOnly = true)
    fun blockerUserIds(blockedUserId: UUID): Set<UUID> = blocks.findBlockerUserIds(blockedUserId)

    @Transactional(readOnly = true)
    fun isHidden(targetType: ModerationTargetType, targetId: UUID): Boolean =
        reports.existsByTargetTypeAndTargetIdAndAction(targetType, targetId, ModerationAction.CONTENT_HIDDEN)
}
