package com.example.mypasture.bible.presentation

import com.example.mypasture.bible.application.BibleChapterCommentary
import com.example.mypasture.bible.application.BibleCommentaryGenerationJob
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** 관리자가 한 장의 해설 생성을 큐에 등록할 때 사용하는 요청 DTO입니다. */
data class QueueBibleCommentaryGenerationRequest(
    @field:NotBlank val version: String,
    @field:NotBlank val bookCode: String,
    @field:Min(1) val chapter: Int,
)

/** 관리자가 품질 점수를 기록하고 초안을 게시할 때 사용하는 요청 DTO입니다. */
data class PublishBibleCommentaryRequest(
    @field:Min(1) @field:Max(100) val qualityScore: Int,
)

/** 생성 비용과 검수 상태를 포함하는 관리자용 해설 응답 DTO입니다. */
data class BibleCommentaryAdminResponse(
    val commentary: BibleChapterCommentaryResponse,
    val status: String,
    val model: String,
    val qualityScore: Int?,
    val promptTokens: Int,
    val completionTokens: Int,
    val estimatedCostUsd: BigDecimal,
    val createdAt: Instant,
)

/** 관리자 생성 큐에서 각 장의 진행 상태와 실패 사유를 보여주는 DTO입니다. */
data class BibleCommentaryGenerationJobResponse(
    val id: UUID,
    val version: String,
    val bookCode: String,
    val chapter: Int,
    val status: String,
    val attemptCount: Int,
    val errorMessage: String?,
    val commentaryId: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

fun BibleChapterCommentary.toAdminResponse(): BibleCommentaryAdminResponse =
    BibleCommentaryAdminResponse(
        commentary = toResponse(),
        status = status.name,
        model = model,
        qualityScore = qualityScore,
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        estimatedCostUsd = estimatedCostUsd,
        createdAt = createdAt,
    )

fun BibleCommentaryGenerationJob.toResponse(): BibleCommentaryGenerationJobResponse =
    BibleCommentaryGenerationJobResponse(
        id,
        version,
        bookCode,
        chapter,
        status.name,
        attemptCount,
        errorMessage,
        commentaryId,
        createdAt,
        updatedAt,
    )
