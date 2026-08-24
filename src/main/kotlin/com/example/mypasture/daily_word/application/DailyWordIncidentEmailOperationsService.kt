package com.example.mypasture.daily_word.application

import com.example.mypasture.admin_audit.domain.AdminAuditAction
import com.example.mypasture.admin_audit.domain.AdminAuditLogEntity
import com.example.mypasture.admin_audit.infrastructure.AdminAuditLogRepository
import com.example.mypasture.daily_word.domain.DailyWordIncidentEmailOutboxEntity
import com.example.mypasture.daily_word.domain.DailyWordIncidentEmailStatus
import com.example.mypasture.daily_word.infrastructure.DailyWordIncidentEmailOutboxRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** 관리자에게 이메일 outbox 상태를 제공하고 실패 delivery 수동 재처리를 감사 가능하게 수행합니다. */
@Service
@Transactional(readOnly = true)
class DailyWordIncidentEmailOperationsService(
    private val repository: DailyWordIncidentEmailOutboxRepository,
    private val auditRepository: AdminAuditLogRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun list(
        status: DailyWordIncidentEmailStatus?,
        incidentId: UUID?,
        from: Instant?,
        to: Instant?,
        page: Int,
        size: Int,
    ): DailyWordIncidentEmailOperations {
        require(page >= 0) { "페이지는 0 이상이어야 합니다." }
        require(size in 1..100) { "페이지 크기는 1에서 100 사이여야 합니다." }
        val end = to ?: Instant.now(clock).plusSeconds(1)
        val start = from ?: end.minus(DEFAULT_RANGE)
        require(start.isBefore(end)) { "조회 시작 시각은 종료 시각보다 빨라야 합니다." }
        require(Duration.between(start, end) <= MAX_RANGE) { "이메일 발송 조회 기간은 최대 31일입니다." }

        val deliveries = repository.findForAdmin(status, incidentId, start, end, PageRequest.of(page, size))
        val counts = repository.summarizeForAdmin(incidentId, start, end).associate { it.status to it.total }
        return DailyWordIncidentEmailOperations(
            from = start,
            to = end,
            generatedAt = Instant.now(clock),
            summary = DailyWordIncidentEmailSummary(
                pending = counts[DailyWordIncidentEmailStatus.PENDING] ?: 0,
                processing = counts[DailyWordIncidentEmailStatus.PROCESSING] ?: 0,
                retryWait = counts[DailyWordIncidentEmailStatus.RETRY_WAIT] ?: 0,
                sent = counts[DailyWordIncidentEmailStatus.SENT] ?: 0,
                dead = counts[DailyWordIncidentEmailStatus.DEAD] ?: 0,
            ),
            deliveries = deliveries.content,
            page = deliveries.number,
            size = deliveries.size,
            totalElements = deliveries.totalElements,
            totalPages = deliveries.totalPages,
            hasNext = deliveries.hasNext(),
        )
    }

    /** operationId 재시도는 같은 결과를 반환하고 다른 요청에 재사용되면 거부합니다. */
    @Transactional
    fun requeue(deliveryId: UUID, adminId: UUID, operationId: UUID, reason: String): DailyWordIncidentEmailOutboxEntity {
        val normalizedReason = reason.trim().also {
            require(it.length in 5..500) { "관리자 조치 사유는 5자에서 500자 사이여야 합니다." }
        }
        val requestFingerprint = fingerprint("REQUEUE", "$deliveryId|$normalizedReason")
        auditRepository.findByOperationId(operationId)?.let { audit ->
            if (
                audit.action != AdminAuditAction.DAILY_WORD_INCIDENT_EMAIL_REQUEUED ||
                audit.targetType != TARGET_TYPE || audit.targetId != deliveryId ||
                audit.requestFingerprint != requestFingerprint
            ) throw DailyWordOperationConflictException()
            return repository.findByIdWithIncident(deliveryId) ?: throw DailyWordNotFoundException("이메일 발송 작업을 찾을 수 없습니다.")
        }

        val delivery = repository.findByIdForUpdate(deliveryId)
            ?: throw DailyWordNotFoundException("이메일 발송 작업을 찾을 수 없습니다.")
        delivery.requeue(Instant.now(clock))
        auditRepository.save(
            AdminAuditLogEntity(
                operationId = operationId,
                adminUserId = adminId,
                action = AdminAuditAction.DAILY_WORD_INCIDENT_EMAIL_REQUEUED,
                targetType = TARGET_TYPE,
                targetId = delivery.id,
                reason = normalizedReason,
                details = "incidentId=${delivery.incident.id};attemptCount=${delivery.attemptCount};status=${delivery.status}",
                requestFingerprint = requestFingerprint,
                createdAt = Instant.now(clock),
            ),
        )
        return delivery
    }

    private fun fingerprint(action: String, canonical: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$action|$canonical".toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        private val DEFAULT_RANGE: Duration = Duration.ofDays(7)
        private val MAX_RANGE: Duration = Duration.ofDays(31)
        private const val TARGET_TYPE = "DAILY_WORD_INCIDENT_EMAIL"
    }
}

/** 필터 기간의 이메일 상태 집계입니다. */
data class DailyWordIncidentEmailSummary(
    val pending: Long,
    val processing: Long,
    val retryWait: Long,
    val sent: Long,
    val dead: Long,
)

/** 관리자 이메일 발송 운영 화면이 한 번에 소비하는 페이지 결과입니다. */
data class DailyWordIncidentEmailOperations(
    val from: Instant,
    val to: Instant,
    val generatedAt: Instant,
    val summary: DailyWordIncidentEmailSummary,
    val deliveries: List<DailyWordIncidentEmailOutboxEntity>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
    val hasNext: Boolean,
)
