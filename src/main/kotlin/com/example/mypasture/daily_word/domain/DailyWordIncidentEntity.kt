package com.example.mypasture.daily_word.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** 오늘의 말씀 운영에서 자동 감지하는 장애 종류입니다. */
enum class DailyWordIncidentType { PUBLISHING_MISSING, GENERATION_FAILED, GENERATION_STALLED }

/** 관리자가 대응 순서를 판단하는 운영 사건 심각도입니다. */
enum class DailyWordIncidentSeverity { WARNING, CRITICAL }

/** 운영 사건이 감지, 확인, 해결되는 상태입니다. */
enum class DailyWordIncidentStatus { OPEN, ACKNOWLEDGED, RESOLVED }

/** 관리자 이메일 outbox 작업의 처리 상태입니다. */
enum class DailyWordIncidentEmailStatus { PENDING, PROCESSING, RETRY_WAIT, SENT, DEAD }

/** 날짜와 유형별로 하나만 유지하며 재발과 심각도 상승을 추적하는 운영 사건입니다. */
@Entity
@Table(
    name = "daily_word_incidents",
    uniqueConstraints = [UniqueConstraint(columnNames = ["incident_type", "word_date"])],
)
class DailyWordIncidentEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "word_date", nullable = false) val wordDate: LocalDate,
    @Enumerated(EnumType.STRING)
    @Column(name = "incident_type", nullable = false) val type: DailyWordIncidentType,
    @Enumerated(EnumType.STRING) var severity: DailyWordIncidentSeverity,
    @Enumerated(EnumType.STRING) var status: DailyWordIncidentStatus = DailyWordIncidentStatus.OPEN,
    @Column(length = 1_000) var message: String,
    @Column(name = "latest_job_id") var latestJobId: UUID? = null,
    @Column(name = "occurrence_count", nullable = false) var occurrenceCount: Int = 1,
    @Column(name = "notification_version", nullable = false) var notificationVersion: Int = 1,
    @Column(name = "first_detected_at", nullable = false) val firstDetectedAt: Instant,
    @Column(name = "last_detected_at", nullable = false) var lastDetectedAt: Instant,
    @Column(name = "acknowledged_by") var acknowledgedBy: UUID? = null,
    @Column(name = "acknowledged_at") var acknowledgedAt: Instant? = null,
    @Column(name = "resolved_by") var resolvedBy: UUID? = null,
    @Column(name = "resolved_at") var resolvedAt: Instant? = null,
    @Column(name = "resolution_note", length = 500) var resolutionNote: String? = null,
    @Version @Column(name = "entity_version") var entityVersion: Long = 0,
) {
    /** 같은 장애를 갱신하고 재발 또는 심각도 상승일 때만 새 알림 버전을 만듭니다. */
    fun observe(nextSeverity: DailyWordIncidentSeverity, nextMessage: String, jobId: UUID?, now: Instant): Boolean {
        val reopened = status == DailyWordIncidentStatus.RESOLVED
        val escalated = nextSeverity.ordinal > severity.ordinal
        if (reopened || (escalated && status == DailyWordIncidentStatus.ACKNOWLEDGED)) {
            status = DailyWordIncidentStatus.OPEN
            acknowledgedBy = null
            acknowledgedAt = null
        }
        if (reopened) {
            resolvedBy = null
            resolvedAt = null
            resolutionNote = null
        }
        if (escalated || reopened) notificationVersion += 1
        severity = if (nextSeverity.ordinal > severity.ordinal || reopened) nextSeverity else severity
        message = nextMessage.take(1_000)
        latestJobId = jobId
        occurrenceCount += 1
        lastDetectedAt = now
        return escalated || reopened
    }

    fun acknowledge(adminId: UUID, now: Instant) {
        check(status == DailyWordIncidentStatus.OPEN) { "열린 운영 사건만 확인 처리할 수 있습니다." }
        status = DailyWordIncidentStatus.ACKNOWLEDGED
        acknowledgedBy = adminId
        acknowledgedAt = now
    }

    fun resolve(adminId: UUID?, note: String, now: Instant) {
        check(status != DailyWordIncidentStatus.RESOLVED) { "이미 해결된 운영 사건입니다." }
        status = DailyWordIncidentStatus.RESOLVED
        resolvedBy = adminId
        resolvedAt = now
        resolutionNote = note.take(500)
    }
}

/** 운영 사건 이메일을 DB에 먼저 저장하고 lease와 fencing token으로 발송하는 outbox입니다. */
@Entity
@Table(
    name = "daily_word_incident_email_outbox",
    uniqueConstraints = [UniqueConstraint(columnNames = ["incident_id", "notification_version", "recipient_email"])],
)
class DailyWordIncidentEmailOutboxEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "incident_id", nullable = false) val incident: DailyWordIncidentEntity,
    @Column(name = "notification_version", nullable = false) val notificationVersion: Int,
    @Column(name = "recipient_user_id", nullable = false) val recipientUserId: UUID,
    @Column(name = "recipient_email", nullable = false, length = 320) val recipientEmail: String,
    @Enumerated(EnumType.STRING) var status: DailyWordIncidentEmailStatus = DailyWordIncidentEmailStatus.PENDING,
    @Column(name = "attempt_count", nullable = false) var attemptCount: Int = 0,
    @Column(name = "next_attempt_at", nullable = false) var nextAttemptAt: Instant,
    @Column(name = "lease_token") var leaseToken: UUID? = null,
    @Column(name = "lease_until") var leaseUntil: Instant? = null,
    @Column(name = "error_message", length = 1_000) var errorMessage: String? = null,
    @Column(name = "sent_at") var sentAt: Instant? = null,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant,
) {
    fun claim(token: UUID, until: Instant, now: Instant) {
        status = DailyWordIncidentEmailStatus.PROCESSING
        attemptCount += 1
        leaseToken = token
        leaseUntil = until
        updatedAt = now
    }

    fun complete(token: UUID, now: Instant): Boolean {
        if (status != DailyWordIncidentEmailStatus.PROCESSING || leaseToken != token) return false
        status = DailyWordIncidentEmailStatus.SENT
        sentAt = now
        errorMessage = null
        clearLease(now)
        return true
    }

    fun fail(token: UUID, message: String, retryAt: Instant?, now: Instant): Boolean {
        if (status != DailyWordIncidentEmailStatus.PROCESSING || leaseToken != token) return false
        status = if (retryAt == null) DailyWordIncidentEmailStatus.DEAD else DailyWordIncidentEmailStatus.RETRY_WAIT
        if (retryAt != null) nextAttemptAt = retryAt
        errorMessage = message.take(1_000)
        clearLease(now)
        return true
    }

    private fun clearLease(now: Instant) {
        leaseToken = null
        leaseUntil = null
        updatedAt = now
    }
}
