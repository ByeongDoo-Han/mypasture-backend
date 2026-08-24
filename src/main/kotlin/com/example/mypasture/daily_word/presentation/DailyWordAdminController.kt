package com.example.mypasture.daily_word.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.daily_word.application.DailyWordService
import com.example.mypasture.daily_word.application.DailyWordOperationsSummary
import com.example.mypasture.daily_word.application.DailyWordIncidentService
import com.example.mypasture.daily_word.application.DailyWordIncidentEmailOperationsService
import com.example.mypasture.daily_word.application.DailyWordIncidentEmailOperations
import com.example.mypasture.daily_word.domain.DailyWordEntity
import com.example.mypasture.daily_word.domain.DailyWordGenerationJobEntity
import com.example.mypasture.daily_word.domain.DailyWordIncidentEntity
import com.example.mypasture.daily_word.domain.DailyWordIncidentStatus
import com.example.mypasture.daily_word.domain.DailyWordIncidentEmailOutboxEntity
import com.example.mypasture.daily_word.domain.DailyWordIncidentEmailStatus
import com.example.mypasture.daily_word.domain.DailyWordStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** 관리자 오늘의 말씀 HTTP 요청을 생성·검수 application service에 전달합니다. */
@RestController
@RequestMapping("/api/v1/admin/daily-words")
class DailyWordAdminController(
    private val service: DailyWordService,
    private val incidentService: DailyWordIncidentService,
    private val incidentEmailService: DailyWordIncidentEmailOperationsService,
) : DailyWordAdminApi {
    override fun list(from: LocalDate?, to: LocalDate?, status: DailyWordStatus?): List<DailyWordAdminResponse> {
        val end = to ?: LocalDate.now(SEOUL).plusDays(14)
        val start = from ?: end.minusDays(30)
        return service.list(start, end, status).map { it.toAdminResponse() }
    }

    override fun jobs() = service.listJobs().map { it.toResponse() }

    override fun operationsSummary(from: LocalDate?, to: LocalDate?): DailyWordOperationsSummaryResponse {
        val today = LocalDate.now(SEOUL)
        val start = from ?: today.minusDays(30)
        val end = to ?: today.plusDays(1)
        return service.operationsSummary(start, end).toResponse()
    }

    override fun incidents(status: DailyWordIncidentStatus?) = incidentService.list(status).map { it.toResponse() }

    override fun incidentEmailDeliveries(
        status: DailyWordIncidentEmailStatus?,
        incidentId: UUID?,
        from: Instant?,
        to: Instant?,
        page: Int,
        size: Int,
    ) = incidentEmailService.list(status, incidentId, from, to, page, size).toResponse()

    override fun requeueIncidentEmail(principal: JwtPrincipal, id: UUID, request: ReviewDailyWordRequest) =
        incidentEmailService.requeue(id, principal.userId, request.operationId, request.reason).toResponse()

    override fun createManualDraft(principal: JwtPrincipal, date: LocalDate, request: CreateManualDailyWordRequest) =
        service.createManualDraft(
            date,
            principal.userId,
            request.operationId,
            request.version,
            request.bookCode,
            request.chapter,
            request.verse,
            request.meditation,
            request.actionQuestion,
            request.replaceExisting,
            request.reason,
        ).toAdminResponse()

    override fun acknowledgeIncident(principal: JwtPrincipal, id: UUID, request: ReviewDailyWordRequest) =
        incidentService.acknowledge(id, principal.userId, request.operationId, request.reason).toResponse()

    override fun resolveIncident(principal: JwtPrincipal, id: UUID, request: ReviewDailyWordRequest) =
        incidentService.resolve(id, principal.userId, request.operationId, request.reason).toResponse()

    override suspend fun generate(principal: JwtPrincipal, date: LocalDate, request: GenerateDailyWordRequest) =
        ResponseEntity.accepted().body(
            service.queueGeneration(date, principal.userId, request.operationId, request.regenerate, request.reason).toResponse(),
        )

    override fun update(principal: JwtPrincipal, id: UUID, request: UpdateDailyWordRequest) =
        service.updateDraft(id, principal.userId, request.operationId, request.verseId, request.meditation, request.actionQuestion, request.reason).toAdminResponse()

    override fun publish(principal: JwtPrincipal, id: UUID, request: ReviewDailyWordRequest) =
        service.publish(id, principal.userId, request.operationId, request.reason).toAdminResponse()

    override fun reject(principal: JwtPrincipal, id: UUID, request: ReviewDailyWordRequest) =
        service.reject(id, principal.userId, request.operationId, request.reason).toAdminResponse()

    companion object { private val SEOUL = ZoneId.of("Asia/Seoul") }
}

private fun DailyWordEntity.toAdminResponse() = DailyWordAdminResponse(
    id, wordDate, verseId, bibleVersion, bookCode, bookName, chapter, verse, verseText,
    meditation, actionQuestion, status, source, contentRevision, model, promptVersion,
    retrievalStrategy?.name, embeddingTokens, promptTokens, completionTokens, estimatedCostUsd,
    reviewedBy, rejectionReason, generatedAt, reviewedAt, publishedAt, updatedAt,
)

private fun DailyWordGenerationJobEntity.toResponse() = DailyWordGenerationJobResponse(
    id, wordDate, status, attemptCount, errorMessage, dailyWordId, createdAt, updatedAt,
)

private fun DailyWordIncidentEntity.toResponse() = DailyWordIncidentResponse(
    id,
    wordDate,
    type,
    severity,
    status,
    message,
    latestJobId,
    occurrenceCount,
    firstDetectedAt,
    lastDetectedAt,
    acknowledgedBy,
    acknowledgedAt,
    resolvedBy,
    resolvedAt,
    resolutionNote,
)

private fun DailyWordIncidentEmailOperations.toResponse() = DailyWordIncidentEmailOperationsResponse(
    from,
    to,
    generatedAt,
    DailyWordIncidentEmailSummaryResponse(summary.pending, summary.processing, summary.retryWait, summary.sent, summary.dead),
    deliveries.map { it.toResponse() },
    page,
    size,
    totalElements,
    totalPages,
    hasNext,
)

private fun DailyWordIncidentEmailOutboxEntity.toResponse() = DailyWordIncidentEmailDeliveryResponse(
    id = id,
    incidentId = incident.id,
    incidentDate = incident.wordDate,
    incidentType = incident.type,
    incidentSeverity = incident.severity,
    notificationVersion = notificationVersion,
    maskedRecipient = maskEmail(recipientEmail),
    status = status,
    attemptCount = attemptCount,
    nextAttemptAt = nextAttemptAt,
    errorSummary = errorMessage?.let(::sanitizeErrorForAdmin),
    sentAt = sentAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    requeueAllowed = status == DailyWordIncidentEmailStatus.DEAD || status == DailyWordIncidentEmailStatus.RETRY_WAIT,
)

private fun maskEmail(value: String): String {
    val local = value.substringBefore('@')
    val domain = value.substringAfter('@', "hidden")
    return "${local.take(2).ifBlank { "*" }}***@$domain"
}

private fun sanitizeErrorForAdmin(value: String): String = value
    .replace(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "[email]")
    .replace(Regex("(?i)(bearer|token|api[-_ ]?key|password)\\s*[:=]?\\s*[^\\s,;]+"), "$1=[redacted]")
    .take(300)

private fun DailyWordOperationsSummary.toResponse() = DailyWordOperationsSummaryResponse(
    from = from,
    to = to,
    generatedAt = generatedAt,
    today = DailyWordReadinessResponse(today.date, today.status, today.publishedAt),
    tomorrow = DailyWordReadinessResponse(tomorrow.date, tomorrow.status, tomorrow.publishedAt),
    content = DailyWordContentSummaryResponse(
        content.draft,
        content.published,
        content.rejected,
        content.missing,
        content.datesNeedingAttention,
    ),
    jobs = DailyWordJobSummaryResponse(
        jobs.pending,
        jobs.processing,
        jobs.completed,
        jobs.failed,
        jobs.cancelled,
        jobs.oldestActiveCreatedAt,
    ),
    usage = DailyWordUsageSummaryResponse(
        usage.embeddingTokens,
        usage.promptTokens,
        usage.completionTokens,
        usage.estimatedCostUsd,
    ),
)
