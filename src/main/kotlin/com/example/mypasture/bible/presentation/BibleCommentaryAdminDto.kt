package com.example.mypasture.bible.presentation

import com.example.mypasture.bible.application.BibleChapterCommentary
import com.example.mypasture.bible.application.BibleCommentaryGenerationJob
import com.example.mypasture.bible.application.BibleCommentaryBookGenerationResult
import com.example.mypasture.bible.application.BibleCommentaryBookCoverage
import com.example.mypasture.bible.application.BibleCommentaryRangeGenerationResult
import com.example.mypasture.bible.application.BibleCommentaryDraftEdit
import com.example.mypasture.bible.application.BibleCommentaryEvidence
import com.example.mypasture.bible.application.BibleCommentaryReviewEvent
import com.example.mypasture.bible.application.CreateBibleCommentaryEvidenceCommand
import com.example.mypasture.bible.application.GeneratedCommentaryReference
import com.example.mypasture.bible.application.GeneratedCommentarySection
import com.example.mypasture.bible.domain.BibleCommentaryEvidenceType
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** 관리자가 한 장의 해설 생성을 큐에 등록할 때 사용하는 요청 DTO입니다. */
data class QueueBibleCommentaryGenerationRequest(
    @field:NotBlank val version: String,
    @field:NotBlank val bookCode: String,
    @field:Min(1) val chapter: Int,
)

/** 관리자가 한 권의 미생성 장을 생성 큐에 일괄 등록할 때 사용하는 요청 DTO입니다. */
data class QueueBibleCommentaryBookGenerationRequest(
    @field:NotBlank val version: String,
    @field:NotBlank val bookCode: String,
    val includePublished: Boolean = false,
)

/** 관리자가 최대 50개 장을 한 번에 사전 생성 등록할 때 사용하는 요청입니다. */
data class QueueBibleCommentaryRangeGenerationRequest(
    @field:NotBlank val version: String,
    @field:NotBlank val bookCode: String,
    @field:Min(1) val startChapter: Int,
    @field:Min(1) val endChapter: Int,
    val includePublished: Boolean = false,
)

/** 관리자가 품질 점수를 기록하고 초안을 게시할 때 사용하는 요청 DTO입니다. */
data class PublishBibleCommentaryRequest(
    @field:Min(1) @field:Max(100) val qualityScore: Int,
    @field:NotBlank @field:Size(min = 5, max = 1000) val reviewNotes: String,
)

/** 관리자가 해설 초안을 반려할 때 남기는 필수 검수 사유입니다. */
data class RejectBibleCommentaryRequest(
    @field:NotBlank @field:Size(min = 5, max = 1000) val reviewNotes: String,
)

/** 관리자 초안 편집에서 절 범위와 설명을 함께 전달하는 DTO입니다. */
data class EditBibleCommentarySectionRequest(
    @field:Min(1) val startVerse: Int,
    @field:Min(1) val endVerse: Int,
    @field:NotBlank val title: String,
    @field:NotBlank val explanation: String,
)

/** 관리자 초안 편집에서 실제 성경 위치만 전달하는 교차 참조 DTO입니다. */
data class EditBibleCommentaryReferenceRequest(
    @field:NotBlank val bookCode: String,
    @field:Min(1) val chapter: Int,
    @field:Min(1) val verse: Int,
    @field:NotBlank val reason: String,
)

/** AI 초안을 수정해 새 검수 revision을 만드는 요청 DTO입니다. */
data class EditBibleCommentaryRequest(
    @field:NotBlank val summary: String,
    @field:NotBlank val historicalContext: String,
    @field:Valid @field:Size(min = 1) val sections: List<EditBibleCommentarySectionRequest>,
    @field:Size(min = 1, max = 8) val keyThemes: List<@NotBlank String>,
    @field:Valid @field:Size(max = 10) val crossReferences: List<EditBibleCommentaryReferenceRequest>,
    @field:Size(min = 1, max = 6) val reflectionQuestions: List<@NotBlank String>,
    @field:Size(max = 6) val cautions: List<@NotBlank String>,
    @field:NotBlank @field:Size(min = 5, max = 1000) val reviewNotes: String,
) {
    fun toCommand() = BibleCommentaryDraftEdit(
        summary = summary,
        historicalContext = historicalContext,
        sections = sections.map { GeneratedCommentarySection(it.startVerse, it.endVerse, it.title, it.explanation) },
        keyThemes = keyThemes,
        crossReferences = crossReferences.map {
            GeneratedCommentaryReference(it.bookCode, it.chapter, it.verse, it.reason)
        },
        reflectionQuestions = reflectionQuestions,
        cautions = cautions,
        reviewNotes = reviewNotes,
    )
}

/** 저작권과 출처를 확인할 배경 메모를 관리자 초안으로 등록하는 요청 DTO입니다. */
data class CreateBibleCommentaryEvidenceRequest(
    @field:NotBlank val version: String,
    @field:NotBlank val bookCode: String,
    @field:Min(1) val chapter: Int?,
    val evidenceType: BibleCommentaryEvidenceType,
    @field:NotBlank @field:Size(max = 200) val title: String,
    @field:NotBlank @field:Size(min = 20, max = 4000) val content: String,
    @field:NotBlank @field:Size(max = 240) val sourceName: String,
    @field:Size(max = 1000) val sourceUrl: String?,
    @field:NotBlank @field:Size(max = 500) val licenseNote: String,
) {
    fun toCommand() = CreateBibleCommentaryEvidenceCommand(
        version, bookCode, chapter, evidenceType, title, content, sourceName, sourceUrl, licenseNote,
    )
}

/** 생성 비용과 검수 상태를 포함하는 관리자용 해설 응답 DTO입니다. */
data class BibleCommentaryAdminResponse(
    val commentary: BibleChapterCommentaryResponse,
    val status: String,
    val model: String,
    val qualityScore: Int?,
    val evaluationStatus: String,
    val evaluatorModel: String?,
    val evaluationScore: Int?,
    val evaluationGroundingScore: Int?,
    val evaluationHistoricalEvidenceScore: Int?,
    val evaluationReferenceRelevanceScore: Int?,
    val evaluationTheologicalNeutralityScore: Int?,
    val evaluationClarityScore: Int?,
    val fabricatedScripture: Boolean,
    val unsupportedHistoricalClaims: Boolean,
    val evaluationIssues: List<String>,
    val evaluationPromptTokens: Int,
    val evaluationCompletionTokens: Int,
    val evaluationCostUsd: BigDecimal,
    val reviewNotes: String?,
    val promptTokens: Int,
    val completionTokens: Int,
    val estimatedCostUsd: BigDecimal,
    val createdAt: Instant,
)

/** 책 단위 생성 요청의 장별 등록 결과를 반환합니다. */
data class BibleCommentaryBookGenerationResponse(
    val version: String,
    val bookCode: String,
    val queuedChapters: List<Int>,
    val skippedChapters: List<Int>,
    val unavailableChapters: List<Int>,
)

/** 장 범위 등록에서 성공·건너뜀·본문 없음 장을 구분합니다. */
data class BibleCommentaryRangeGenerationResponse(
    val version: String,
    val bookCode: String,
    val startChapter: Int,
    val endChapter: Int,
    val queuedChapters: List<Int>,
    val skippedChapters: List<Int>,
    val unavailableChapters: List<Int>,
)

/** 관리자에게 장별 적재·게시 현황을 전달합니다. */
data class BibleCommentaryBookCoverageResponse(
    val version: String,
    val bookCode: String,
    val chapters: List<BibleCommentaryChapterCoverageResponse>,
)

/** 한 장의 해설 및 생성 작업 상태를 전달합니다. */
data class BibleCommentaryChapterCoverageResponse(
    val chapter: Int,
    val status: String,
    val publishedRevision: Int?,
    val draftRevision: Int?,
    val latestJobStatus: String?,
)

/** 관리자에게 출처와 승인 상태를 함께 보여주는 해설 근거 응답입니다. */
data class BibleCommentaryEvidenceResponse(
    val id: UUID,
    val version: String,
    val bookCode: String,
    val chapter: Int?,
    val evidenceType: String,
    val title: String,
    val content: String,
    val sourceName: String,
    val sourceUrl: String?,
    val licenseNote: String,
    val status: String,
    val createdBy: UUID,
    val approvedBy: UUID?,
    val approvedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** 해설의 수정·게시·반려 담당자와 사유를 반환하는 감사 응답입니다. */
data class BibleCommentaryReviewEventResponse(
    val id: Long,
    val commentaryId: UUID,
    val previousCommentaryId: UUID?,
    val action: String,
    val reviewerId: UUID,
    val notes: String,
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
        evaluationStatus = evaluationStatus.name,
        evaluatorModel = evaluatorModel,
        evaluationScore = evaluationScore,
        evaluationGroundingScore = evaluationGroundingScore,
        evaluationHistoricalEvidenceScore = evaluationHistoricalEvidenceScore,
        evaluationReferenceRelevanceScore = evaluationReferenceRelevanceScore,
        evaluationTheologicalNeutralityScore = evaluationTheologicalNeutralityScore,
        evaluationClarityScore = evaluationClarityScore,
        fabricatedScripture = fabricatedScripture,
        unsupportedHistoricalClaims = unsupportedHistoricalClaims,
        evaluationIssues = evaluationIssues,
        evaluationPromptTokens = evaluationPromptTokens,
        evaluationCompletionTokens = evaluationCompletionTokens,
        evaluationCostUsd = evaluationCostUsd,
        reviewNotes = reviewNotes,
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        estimatedCostUsd = estimatedCostUsd,
        createdAt = createdAt,
    )

fun BibleCommentaryBookGenerationResult.toResponse() = BibleCommentaryBookGenerationResponse(
    version, bookCode, queuedChapters, skippedChapters, unavailableChapters,
)

fun BibleCommentaryRangeGenerationResult.toResponse() = BibleCommentaryRangeGenerationResponse(
    version, bookCode, startChapter, endChapter, queuedChapters, skippedChapters, unavailableChapters,
)

fun BibleCommentaryBookCoverage.toResponse() = BibleCommentaryBookCoverageResponse(
    version, bookCode, chapters.map {
        BibleCommentaryChapterCoverageResponse(
            it.chapter, it.status.name, it.publishedRevision, it.draftRevision, it.latestJobStatus?.name,
        )
    },
)

fun BibleCommentaryEvidence.toResponse() = BibleCommentaryEvidenceResponse(
    id, version, bookCode, chapter, evidenceType.name, title, content, sourceName, sourceUrl,
    licenseNote, status.name, createdBy, approvedBy, approvedAt, createdAt, updatedAt,
)

fun BibleCommentaryReviewEvent.toResponse() = BibleCommentaryReviewEventResponse(
    id, commentaryId, previousCommentaryId, action.name, reviewerId, notes, createdAt,
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
