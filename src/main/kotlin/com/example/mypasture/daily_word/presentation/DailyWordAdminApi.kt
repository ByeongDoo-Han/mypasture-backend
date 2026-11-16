package com.example.mypasture.daily_word.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.daily_word.domain.DailyWordGenerationJobStatus
import com.example.mypasture.daily_word.domain.DailyWordSource
import com.example.mypasture.daily_word.domain.DailyWordStatus
import com.example.mypasture.daily_word.domain.DailyWordIncidentSeverity
import com.example.mypasture.daily_word.domain.DailyWordIncidentStatus
import com.example.mypasture.daily_word.domain.DailyWordIncidentType
import com.example.mypasture.daily_word.domain.DailyWordIncidentEmailStatus
import com.example.mypasture.daily_word.application.DailyWordReadinessStatus
import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.format.annotation.DateTimeFormat
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Swagger 문서와 구현을 분리한 오늘의 말씀 생성·검수 관리자 API 계약입니다. */
@Tag(name = "관리자 · 오늘의 말씀", description = "오늘의 말씀 생성 작업, 초안, 장애와 발행을 관리합니다.")
interface DailyWordAdminApi {
    @Operation(summary = "오늘의 말씀 초안 목록", description = "관리자가 날짜와 상태로 오늘의 말씀 목록을 조회합니다.")
    @GetMapping
    fun list(@Parameter(description = "조회 범위 시작 날짜(YYYY-MM-DD)(포함).") @RequestParam from: LocalDate?, @Parameter(description = "조회 범위 종료 날짜(YYYY-MM-DD)(포함).") @RequestParam to: LocalDate?, @Parameter(description = "결과를 제한할 처리 상태.") @RequestParam status: DailyWordStatus?): List<DailyWordAdminResponse>

    @Operation(summary = "말씀 생성 작업 조회", description = "관리자가 오늘의 말씀 생성 작업과 처리 상태를 조회합니다.")
    @GetMapping("/generation-jobs")
    fun jobs(): List<DailyWordGenerationJobResponse>

    @Operation(summary = "말씀 운영 현황 요약", description = "지정 기간의 오늘의 말씀 운영 지표를 조회합니다.")
    @GetMapping("/operations-summary")
    fun operationsSummary(
        @Parameter(description = "조회 범위 시작 날짜(YYYY-MM-DD)(포함).") @RequestParam from: LocalDate?,
        @Parameter(description = "조회 범위 종료 날짜(YYYY-MM-DD)(포함).") @RequestParam to: LocalDate?,
    ): DailyWordOperationsSummaryResponse

    @Operation(summary = "말씀 운영 장애 목록", description = "관리자가 오늘의 말씀 운영 장애를 상태별로 조회합니다.")
    @GetMapping("/incidents")
    fun incidents(@Parameter(description = "결과를 제한할 처리 상태.") @RequestParam status: DailyWordIncidentStatus?): List<DailyWordIncidentResponse>

    @Operation(summary = "장애 이메일 발송 내역", description = "관리자가 장애 알림 이메일의 전송 상태와 내역을 조회합니다.")
    @GetMapping("/incident-email-deliveries")
    fun incidentEmailDeliveries(
        @Parameter(description = "결과를 제한할 처리 상태.") @RequestParam status: DailyWordIncidentEmailStatus?,
        @Parameter(description = "발송 내역을 조회할 운영 장애 UUID.") @RequestParam incidentId: UUID?,
        @Parameter(description = "조회 범위 시작 시각(ISO-8601)(포함).") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: Instant?,
        @Parameter(description = "조회 범위 종료 시각(ISO-8601)(포함).") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: Instant?,
        @Parameter(description = "0부터 시작하는 페이지 번호.") @RequestParam(defaultValue = "0") page: Int,
        @Parameter(description = "한 번에 반환할 최대 항목 수.") @RequestParam(defaultValue = "20") size: Int,
    ): DailyWordIncidentEmailOperationsResponse

    @Operation(summary = "장애 이메일 재발송 예약", description = "관리자가 실패한 장애 알림 이메일을 다시 발송 대기열에 넣습니다.")
    @PostMapping("/incident-email-deliveries/{id}/requeue")
    fun requeueIncidentEmail(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Parameter(description = "재발송 대기열에 넣을 장애 이메일 UUID.") @PathVariable id: UUID,
        @Valid @RequestBody request: ReviewDailyWordRequest,
    ): DailyWordIncidentEmailDeliveryResponse

    @Operation(summary = "오늘의 말씀 수동 초안 작성", description = "관리자가 지정 날짜의 말씀을 선택해 수동 초안을 만듭니다.")
    @PostMapping("/{date}/manual-drafts")
    fun createManualDraft(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Parameter(description = "수동 초안을 작성할 날짜(YYYY-MM-DD).") @PathVariable date: LocalDate,
        @Valid @RequestBody request: CreateManualDailyWordRequest,
    ): DailyWordAdminResponse

    @Operation(summary = "말씀 장애 확인 처리", description = "관리자가 장애를 확인했음을 기록합니다.")
    @PostMapping("/incidents/{id}/acknowledge")
    fun acknowledgeIncident(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Parameter(description = "확인 처리할 운영 장애 UUID.") @PathVariable id: UUID,
        @Valid @RequestBody request: ReviewDailyWordRequest,
    ): DailyWordIncidentResponse

    @Operation(summary = "말씀 장애 해결 처리", description = "관리자가 장애의 해결 상태와 검토 내용을 기록합니다.")
    @PostMapping("/incidents/{id}/resolve")
    fun resolveIncident(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Parameter(description = "해결 처리할 운영 장애 UUID.") @PathVariable id: UUID,
        @Valid @RequestBody request: ReviewDailyWordRequest,
    ): DailyWordIncidentResponse

    @Operation(summary = "오늘의 말씀 생성 요청", description = "관리자가 지정 날짜의 AI 말씀 생성 작업을 대기열에 등록합니다.")
    @PostMapping("/{date}/generation-jobs")
    suspend fun generate(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Parameter(description = "말씀 생성을 요청할 날짜(YYYY-MM-DD).") @PathVariable date: LocalDate,
        @Valid @RequestBody request: GenerateDailyWordRequest,
    ): ResponseEntity<DailyWordGenerationJobResponse>

    @Operation(summary = "오늘의 말씀 초안 수정", description = "관리자가 선택한 말씀 초안의 내용을 수정합니다.")
    @PutMapping("/{id}")
    fun update(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Parameter(description = "수정할 오늘의 말씀 초안 UUID.") @PathVariable id: UUID,
        @Valid @RequestBody request: UpdateDailyWordRequest,
    ): DailyWordAdminResponse

    @Operation(summary = "오늘의 말씀 발행", description = "관리자가 초안을 검수해 공개 상태로 전환합니다.")
    @PostMapping("/{id}/publish")
    fun publish(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Parameter(description = "발행할 오늘의 말씀 초안 UUID.") @PathVariable id: UUID,
        @Valid @RequestBody request: ReviewDailyWordRequest,
    ): DailyWordAdminResponse

    @Operation(summary = "오늘의 말씀 반려", description = "관리자가 초안을 반려하고 검토 의견을 남깁니다.")
    @PostMapping("/{id}/reject")
    fun reject(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Parameter(description = "반려할 오늘의 말씀 초안 UUID.") @PathVariable id: UUID,
        @Valid @RequestBody request: ReviewDailyWordRequest,
    ): DailyWordAdminResponse
}

data class GenerateDailyWordRequest(val operationId: UUID, val regenerate: Boolean = false, @field:Size(min = 5, max = 500) val reason: String)

data class UpdateDailyWordRequest(
    val operationId: UUID,
    @field:Min(1) val verseId: Long,
    @field:Size(min = 30, max = 1_000) val meditation: String,
    @field:Size(min = 10, max = 300) val actionQuestion: String,
    @field:Size(min = 5, max = 500) val reason: String,
)

data class ReviewDailyWordRequest(val operationId: UUID, @field:Size(min = 5, max = 500) val reason: String)

data class CreateManualDailyWordRequest(
    val operationId: UUID,
    @field:Size(min = 1, max = 20) val version: String,
    @field:Size(min = 2, max = 20) val bookCode: String,
    @field:Min(1) val chapter: Int,
    @field:Min(1) val verse: Int,
    @field:Size(min = 30, max = 1_000) val meditation: String,
    @field:Size(min = 10, max = 300) val actionQuestion: String,
    val replaceExisting: Boolean = false,
    @field:Size(min = 5, max = 500) val reason: String,
)

data class DailyWordAdminResponse(
    val id: UUID,
    val date: LocalDate,
    val verseId: Long?,
    val version: String,
    val bookCode: String,
    val bookName: String,
    val chapter: Int,
    val verse: Int,
    val verseText: String,
    val meditation: String,
    val actionQuestion: String,
    val status: DailyWordStatus,
    val source: DailyWordSource,
    val contentRevision: Int,
    val model: String?,
    val promptVersion: String?,
    val retrievalStrategy: String?,
    val embeddingTokens: Int,
    val promptTokens: Int,
    val completionTokens: Int,
    val estimatedCostUsd: BigDecimal,
    val reviewedBy: UUID?,
    val rejectionReason: String?,
    val generatedAt: Instant?,
    val reviewedAt: Instant?,
    val publishedAt: Instant?,
    val updatedAt: Instant,
    val endVerse: Int? = null,
    val passageText: String? = null,
    val topics: List<String> = emptyList(),
    val readingMode: String? = null,
    val passageType: String? = null,
)

data class DailyWordGenerationJobResponse(
    val id: UUID,
    val date: LocalDate,
    val status: DailyWordGenerationJobStatus,
    val attemptCount: Int,
    val errorMessage: String?,
    val dailyWordId: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** 게시 누락 또는 생성 장애와 관리자 대응 상태를 반환합니다. */
data class DailyWordIncidentResponse(
    val id: UUID,
    val date: LocalDate,
    val type: DailyWordIncidentType,
    val severity: DailyWordIncidentSeverity,
    val status: DailyWordIncidentStatus,
    val message: String,
    val latestJobId: UUID?,
    val occurrenceCount: Int,
    val firstDetectedAt: Instant,
    val lastDetectedAt: Instant,
    val acknowledgedBy: UUID?,
    val acknowledgedAt: Instant?,
    val resolvedBy: UUID?,
    val resolvedAt: Instant?,
    val resolutionNote: String?,
)

/** 이메일 outbox 페이지, 상태 집계와 필터 범위를 반환합니다. */
data class DailyWordIncidentEmailOperationsResponse(
    val from: Instant,
    val to: Instant,
    val generatedAt: Instant,
    val summary: DailyWordIncidentEmailSummaryResponse,
    val deliveries: List<DailyWordIncidentEmailDeliveryResponse>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
    val hasNext: Boolean,
)

/** 이메일 outbox 상태별 건수입니다. */
data class DailyWordIncidentEmailSummaryResponse(
    val pending: Long,
    val processing: Long,
    val retryWait: Long,
    val sent: Long,
    val dead: Long,
)

/** 수신자와 외부 오류를 제한적으로 노출하는 관리자용 이메일 delivery 응답입니다. */
data class DailyWordIncidentEmailDeliveryResponse(
    val id: UUID,
    val incidentId: UUID,
    val incidentDate: LocalDate,
    val incidentType: DailyWordIncidentType,
    val incidentSeverity: DailyWordIncidentSeverity,
    val notificationVersion: Int,
    val maskedRecipient: String,
    val status: DailyWordIncidentEmailStatus,
    val attemptCount: Int,
    val nextAttemptAt: Instant,
    val errorSummary: String?,
    val sentAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val requeueAllowed: Boolean,
)

/** 관리자 대시보드가 사용하는 게시 준비, 작업 건전성과 비용 집계 응답입니다. */
data class DailyWordOperationsSummaryResponse(
    val from: LocalDate,
    val to: LocalDate,
    val generatedAt: Instant,
    val today: DailyWordReadinessResponse,
    val tomorrow: DailyWordReadinessResponse,
    val content: DailyWordContentSummaryResponse,
    val jobs: DailyWordJobSummaryResponse,
    val usage: DailyWordUsageSummaryResponse,
)

/** 특정 날짜 콘텐츠가 사용자 노출 준비를 마쳤는지 나타냅니다. */
data class DailyWordReadinessResponse(val date: LocalDate, val status: DailyWordReadinessStatus, val publishedAt: Instant?)

/** 조회 범위의 콘텐츠 상태와 앞으로 조치할 날짜를 제공합니다. */
data class DailyWordContentSummaryResponse(
    val draft: Int,
    val published: Int,
    val rejected: Int,
    val missing: Int,
    val datesNeedingAttention: List<LocalDate>,
)

/** 생성 작업 상태와 가장 오래된 활성 작업 시각을 제공합니다. */
data class DailyWordJobSummaryResponse(
    val pending: Long,
    val processing: Long,
    val completed: Long,
    val failed: Long,
    val cancelled: Long,
    val oldestActiveCreatedAt: Instant?,
)

/** 저장된 오늘의 말씀 생성 토큰과 예상 비용의 합계를 제공합니다. */
data class DailyWordUsageSummaryResponse(
    val embeddingTokens: Long,
    val promptTokens: Long,
    val completionTokens: Long,
    val estimatedCostUsd: BigDecimal,
)
