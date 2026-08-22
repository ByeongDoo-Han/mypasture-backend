package com.example.mypasture.quiet_time.presentation

import com.example.mypasture.admin_audit.domain.AdminAuditAction
import com.example.mypasture.quiet_time.application.QuietTimeAdminSessionDetail
import com.example.mypasture.quiet_time.application.QuietTimeAdminSessionPage
import com.example.mypasture.quiet_time.application.QuietTimeAdminSessionSummary
import com.example.mypasture.quiet_time.application.QuietTimeAdminStatistics
import com.example.mypasture.quiet_time.domain.QuietTimeQrStatus
import com.example.mypasture.quiet_time.domain.QuietTimeSessionScope
import com.example.mypasture.quiet_time.domain.QuietTimeSessionStatus
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** 관리자 조치의 멱등 키와 감사 사유를 전달하는 요청입니다. */
data class QuietTimeAdminActionRequest(
    val operationId: UUID,
    @field:Size(min = 5, max = 500)
    val reason: String,
)

/** 관리자 QT 목록의 페이지 메타데이터와 세션 요약입니다. */
data class QuietTimeAdminSessionPageResponse(
    val content: List<QuietTimeAdminSessionSummaryResponse>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)

/** 묵상·답변 원문을 포함하지 않는 관리자 QT 목록 항목입니다. */
data class QuietTimeAdminSessionSummaryResponse(
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

/** 관리자 QT 상세와 QR 상태, 최근 감사 로그를 반환합니다. */
data class QuietTimeAdminSessionDetailResponse(
    val summary: QuietTimeAdminSessionSummaryResponse,
    val passageTitle: String,
    val qrStatus: QuietTimeQrStatus?,
    val qrExpiresAt: Instant?,
    val recentActions: List<QuietTimeAdminAuditResponse>,
)

/** 관리자 QT 상세에서 확인하는 최근 운영 조치입니다. */
data class QuietTimeAdminAuditResponse(
    val id: UUID,
    val adminUserId: UUID,
    val action: AdminAuditAction,
    val reason: String,
    val details: String,
    val createdAt: Instant,
)

/** 지정 기간의 QT 운영 지표입니다. */
data class QuietTimeAdminStatisticsResponse(
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

fun QuietTimeAdminSessionPage.toResponse() = QuietTimeAdminSessionPageResponse(
    content = content.map { it.toResponse() },
    page = page,
    size = size,
    totalElements = totalElements,
    totalPages = totalPages,
)

fun QuietTimeAdminSessionSummary.toResponse() = QuietTimeAdminSessionSummaryResponse(
    id = id,
    scope = scope,
    status = status,
    title = title,
    pastureName = pastureName,
    managerDisplayName = managerDisplayName,
    passageReference = passageReference,
    meetingDate = meetingDate,
    meetingStartsAt = meetingStartsAt,
    meetingEndsAt = meetingEndsAt,
    participantCount = participantCount,
    answerCount = answerCount,
    createdAt = createdAt,
)

fun QuietTimeAdminSessionDetail.toResponse() = QuietTimeAdminSessionDetailResponse(
    summary = summary.toResponse(),
    passageTitle = passageTitle,
    qrStatus = qrStatus,
    qrExpiresAt = qrExpiresAt,
    recentActions = recentActions.map {
        QuietTimeAdminAuditResponse(
            id = it.id,
            adminUserId = it.adminUserId,
            action = it.action,
            reason = it.reason,
            details = it.details,
            createdAt = it.createdAt,
        )
    },
)

fun QuietTimeAdminStatistics.toResponse() = QuietTimeAdminStatisticsResponse(
    fromDate = fromDate,
    toDate = toDate,
    totalSessions = totalSessions,
    openSessions = openSessions,
    closedSessions = closedSessions,
    pastureSessions = pastureSessions,
    standaloneSessions = standaloneSessions,
    participants = participants,
    answers = answers,
)
