package com.example.mypasture.daily_word.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.daily_word.domain.DailyWordGenerationJobStatus
import com.example.mypasture.daily_word.domain.DailyWordSource
import com.example.mypasture.daily_word.domain.DailyWordStatus
import com.example.mypasture.daily_word.application.DailyWordReadinessStatus
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
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Swagger 문서와 구현을 분리한 오늘의 말씀 생성·검수 관리자 API 계약입니다. */
interface DailyWordAdminApi {
    @GetMapping
    fun list(@RequestParam from: LocalDate?, @RequestParam to: LocalDate?, @RequestParam status: DailyWordStatus?): List<DailyWordAdminResponse>

    @GetMapping("/generation-jobs")
    fun jobs(): List<DailyWordGenerationJobResponse>

    @GetMapping("/operations-summary")
    fun operationsSummary(
        @RequestParam from: LocalDate?,
        @RequestParam to: LocalDate?,
    ): DailyWordOperationsSummaryResponse

    @PostMapping("/{date}/generation-jobs")
    suspend fun generate(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @PathVariable date: LocalDate,
        @Valid @RequestBody request: GenerateDailyWordRequest,
    ): ResponseEntity<DailyWordGenerationJobResponse>

    @PutMapping("/{id}")
    fun update(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @PathVariable id: UUID,
        @Valid @RequestBody request: UpdateDailyWordRequest,
    ): DailyWordAdminResponse

    @PostMapping("/{id}/publish")
    fun publish(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @PathVariable id: UUID,
        @Valid @RequestBody request: ReviewDailyWordRequest,
    ): DailyWordAdminResponse

    @PostMapping("/{id}/reject")
    fun reject(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @PathVariable id: UUID,
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
    val oldestActiveCreatedAt: Instant?,
)

/** 저장된 오늘의 말씀 생성 토큰과 예상 비용의 합계를 제공합니다. */
data class DailyWordUsageSummaryResponse(
    val embeddingTokens: Long,
    val promptTokens: Long,
    val completionTokens: Long,
    val estimatedCostUsd: BigDecimal,
)
