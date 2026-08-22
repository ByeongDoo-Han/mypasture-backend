package com.example.mypasture.quiet_time.application

import com.example.mypasture.admin_audit.domain.AdminAuditAction
import com.example.mypasture.admin_audit.domain.AdminAuditLogEntity
import com.example.mypasture.admin_audit.infrastructure.AdminAuditLogRepository
import com.example.mypasture.quiet_time.domain.QuietTimeQrStatus
import com.example.mypasture.quiet_time.domain.QuietTimeSessionEntity
import com.example.mypasture.quiet_time.domain.QuietTimeSessionScope
import com.example.mypasture.quiet_time.domain.QuietTimeSessionStatus
import com.example.mypasture.quiet_time.infrastructure.QuietTimeParticipantRepository
import com.example.mypasture.quiet_time.infrastructure.QuietTimeQrInvitationRepository
import com.example.mypasture.quiet_time.infrastructure.QuietTimeQuestionAnswerRepository
import com.example.mypasture.quiet_time.infrastructure.QuietTimeSessionRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/** 관리자가 QT 세션을 검색하고 안전하게 종료·QR 폐기하도록 조정하는 application service입니다. */
@Service
class QuietTimeAdminService(
    private val sessionRepository: QuietTimeSessionRepository,
    private val participantRepository: QuietTimeParticipantRepository,
    private val answerRepository: QuietTimeQuestionAnswerRepository,
    private val qrInvitationRepository: QuietTimeQrInvitationRepository,
    private val auditLogRepository: AdminAuditLogRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional(readOnly = true)
    fun listSessions(
        scope: QuietTimeSessionScope?,
        status: QuietTimeSessionStatus?,
        fromDate: LocalDate?,
        toDate: LocalDate?,
        search: String?,
        page: Int,
        size: Int,
    ): QuietTimeAdminSessionPage {
        require(page >= 0) { "Page must not be negative." }
        require(size in 1..100) { "Size must be between 1 and 100." }
        validateDateRange(fromDate, toDate)
        val normalizedSearch = search?.trim()?.takeIf { it.isNotBlank() }
        require(normalizedSearch == null || normalizedSearch.length <= 100) { "Search must not exceed 100 characters." }

        val result = sessionRepository.findAdminPage(
            scope = scope,
            status = status,
            fromDate = fromDate,
            toDate = toDate,
            search = normalizedSearch ?: "",
            pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")),
        )
        val sessionIds = result.content.map { it.id }
        val participantCounts = groupedCounts(sessionIds, participantRepository::countGroupedBySessionIds)
        val answerCounts = groupedCounts(sessionIds, answerRepository::countGroupedBySessionIds)

        return QuietTimeAdminSessionPage(
            content = result.content.map { session ->
                session.toSummary(
                    participantCount = participantCounts[session.id] ?: 0,
                    answerCount = answerCounts[session.id] ?: 0,
                )
            },
            page = result.number,
            size = result.size,
            totalElements = result.totalElements,
            totalPages = result.totalPages,
        )
    }

    @Transactional(readOnly = true)
    fun getSession(sessionId: UUID): QuietTimeAdminSessionDetail =
        sessionRepository.findById(sessionId)
            .orElseThrow { QuietTimeNotFoundException("Quiet time session was not found: $sessionId") }
            .toDetail()

    @Transactional(readOnly = true)
    fun getStatistics(fromDate: LocalDate?, toDate: LocalDate?): QuietTimeAdminStatistics {
        val today = LocalDate.now(clock.withZone(ZoneOffset.UTC))
        val from = fromDate ?: today.minusDays(DEFAULT_STATISTICS_DAYS - 1)
        val to = toDate ?: today
        validateDateRange(from, to)
        return QuietTimeAdminStatistics(
            fromDate = from,
            toDate = to,
            totalSessions = sessionRepository.countByMeetingDateBetween(from, to),
            openSessions = sessionRepository.countByMeetingDateBetweenAndStatus(from, to, QuietTimeSessionStatus.OPEN),
            closedSessions = sessionRepository.countByMeetingDateBetweenAndStatus(from, to, QuietTimeSessionStatus.CLOSED),
            pastureSessions = sessionRepository.countByMeetingDateBetweenAndScope(from, to, QuietTimeSessionScope.PASTURE),
            standaloneSessions = sessionRepository.countByMeetingDateBetweenAndScope(from, to, QuietTimeSessionScope.STANDALONE),
            participants = participantRepository.countBySessionMeetingDateBetween(from, to),
            answers = answerRepository.countBySessionMeetingDateBetween(from, to),
        )
    }

    @Transactional
    fun forceClose(
        adminUserId: UUID,
        sessionId: UUID,
        operationId: UUID,
        reason: String,
    ): QuietTimeAdminSessionDetail {
        val session = findForUpdate(sessionId)
        existingOperation(operationId, AdminAuditAction.QUIET_TIME_FORCE_CLOSED, sessionId)?.let {
            return session.toDetail()
        }
        val normalizedReason = validateReason(reason)
        val previousStatus = session.status
        session.close()
        auditLogRepository.save(
            AdminAuditLogEntity(
                operationId = operationId,
                adminUserId = adminUserId,
                action = AdminAuditAction.QUIET_TIME_FORCE_CLOSED,
                targetType = TARGET_TYPE,
                targetId = sessionId,
                reason = normalizedReason,
                details = "previousStatus=$previousStatus;currentStatus=${session.status}",
                createdAt = Instant.now(clock),
            ),
        )
        return session.toDetail()
    }

    @Transactional
    fun revokeQr(
        adminUserId: UUID,
        sessionId: UUID,
        operationId: UUID,
        reason: String,
    ): QuietTimeAdminSessionDetail {
        val session = findForUpdate(sessionId)
        existingOperation(operationId, AdminAuditAction.QUIET_TIME_QR_REVOKED, sessionId)?.let {
            return session.toDetail()
        }
        val normalizedReason = validateReason(reason)
        val now = Instant.now(clock)
        val activeInvitations = qrInvitationRepository.findActiveBySessionId(sessionId, now)
        activeInvitations.forEach { it.revoke(now) }
        auditLogRepository.save(
            AdminAuditLogEntity(
                operationId = operationId,
                adminUserId = adminUserId,
                action = AdminAuditAction.QUIET_TIME_QR_REVOKED,
                targetType = TARGET_TYPE,
                targetId = sessionId,
                reason = normalizedReason,
                details = "revokedInvitations=${activeInvitations.size}",
                createdAt = now,
            ),
        )
        return session.toDetail()
    }

    private fun findForUpdate(sessionId: UUID): QuietTimeSessionEntity =
        sessionRepository.findByIdForUpdate(sessionId)
            ?: throw QuietTimeNotFoundException("Quiet time session was not found: $sessionId")

    private fun existingOperation(
        operationId: UUID,
        expectedAction: AdminAuditAction,
        expectedTargetId: UUID,
    ): AdminAuditLogEntity? =
        auditLogRepository.findByOperationId(operationId)?.also { existing ->
            if (existing.action != expectedAction || existing.targetId != expectedTargetId) {
                throw QuietTimeAdminOperationConflictException("Operation id is already used for another admin action.")
            }
        }

    private fun validateReason(reason: String): String =
        reason.trim().also { normalized ->
            require(normalized.length in 5..500) { "Reason length must be between 5 and 500." }
        }

    private fun validateDateRange(fromDate: LocalDate?, toDate: LocalDate?) {
        if (fromDate == null || toDate == null) return
        require(!fromDate.isAfter(toDate)) { "From date must not be after to date." }
        require(ChronoUnit.DAYS.between(fromDate, toDate) <= MAX_DATE_RANGE_DAYS) {
            "Date range must not exceed ${MAX_DATE_RANGE_DAYS + 1} days."
        }
    }

    private fun groupedCounts(
        sessionIds: List<UUID>,
        loader: (Collection<UUID>) -> List<com.example.mypasture.quiet_time.infrastructure.QuietTimeSessionCountProjection>,
    ): Map<UUID, Long> =
        if (sessionIds.isEmpty()) emptyMap() else loader(sessionIds).associate { it.sessionId to it.total }

    private fun QuietTimeSessionEntity.toSummary(
        participantCount: Long,
        answerCount: Long,
    ) = QuietTimeAdminSessionSummary(
        id = id,
        scope = scope,
        status = status,
        title = title,
        pastureName = pasture?.name,
        managerDisplayName = managerDisplayName(),
        passageReference = "${passage.bookName} ${passage.startChapter}:${passage.startVerse}-${passage.endChapter}:${passage.endVerse}",
        meetingDate = meetingDate,
        meetingStartsAt = meetingStartsAt,
        meetingEndsAt = meetingEndsAt,
        participantCount = participantCount,
        answerCount = answerCount,
        createdAt = createdAt,
    )

    private fun QuietTimeSessionEntity.toDetail(): QuietTimeAdminSessionDetail {
        val now = Instant.now(clock)
        val invitation = qrInvitationRepository.findTopBySessionIdOrderByCreatedAtDesc(id)
        val audits = auditLogRepository.findTop20ByTargetTypeAndTargetIdOrderByCreatedAtDesc(TARGET_TYPE, id)
        return QuietTimeAdminSessionDetail(
            summary = toSummary(
                participantCount = participantRepository.countBySessionId(id),
                answerCount = answerRepository.countBySessionId(id),
            ),
            passageTitle = passage.title,
            qrStatus = invitation?.statusAt(now),
            qrExpiresAt = invitation?.expiresAt,
            recentActions = audits.map {
                QuietTimeAdminAuditView(
                    id = it.id,
                    adminUserId = it.adminUserId,
                    action = it.action,
                    reason = it.reason,
                    details = it.details,
                    createdAt = it.createdAt,
                )
            },
        )
    }

    companion object {
        private const val TARGET_TYPE = "QUIET_TIME_SESSION"
        private const val DEFAULT_STATISTICS_DAYS = 30L
        private const val MAX_DATE_RANGE_DAYS = 365L
    }
}

/** 관리자 QT 세션 목록의 페이징 결과입니다. */
data class QuietTimeAdminSessionPage(
    val content: List<QuietTimeAdminSessionSummary>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)

/** 민감한 묵상 원문을 제외한 관리자 QT 세션 요약입니다. */
data class QuietTimeAdminSessionSummary(
    val id: UUID,
    val scope: QuietTimeSessionScope,
    val status: QuietTimeSessionStatus,
    val title: String,
    val pastureName: String?,
    val managerDisplayName: String,
    val passageReference: String,
    val meetingDate: LocalDate,
    val meetingStartsAt: Instant,
    val meetingEndsAt: Instant,
    val participantCount: Long,
    val answerCount: Long,
    val createdAt: Instant,
)

/** 관리자 QT 상세 화면에 필요한 QR 상태와 최근 감사 이력을 포함합니다. */
data class QuietTimeAdminSessionDetail(
    val summary: QuietTimeAdminSessionSummary,
    val passageTitle: String,
    val qrStatus: QuietTimeQrStatus?,
    val qrExpiresAt: Instant?,
    val recentActions: List<QuietTimeAdminAuditView>,
)

/** QT 세션에 수행된 최근 관리자 조치입니다. */
data class QuietTimeAdminAuditView(
    val id: UUID,
    val adminUserId: UUID,
    val action: AdminAuditAction,
    val reason: String,
    val details: String,
    val createdAt: Instant,
)

/** 지정 기간의 QT 운영량을 원문 노출 없이 집계한 결과입니다. */
data class QuietTimeAdminStatistics(
    val fromDate: LocalDate,
    val toDate: LocalDate,
    val totalSessions: Long,
    val openSessions: Long,
    val closedSessions: Long,
    val pastureSessions: Long,
    val standaloneSessions: Long,
    val participants: Long,
    val answers: Long,
)

/** 동일한 관리자 멱등 키가 다른 작업에 재사용되었음을 나타냅니다. */
class QuietTimeAdminOperationConflictException(message: String) : RuntimeException(message)
