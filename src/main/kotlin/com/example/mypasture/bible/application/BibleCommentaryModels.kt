package com.example.mypasture.bible.application

import com.example.mypasture.bible.domain.BibleCommentaryJobStatus
import com.example.mypasture.bible.domain.BibleCommentaryEvaluationStatus
import com.example.mypasture.bible.domain.BibleCommentaryEvidenceStatus
import com.example.mypasture.bible.domain.BibleCommentaryEvidenceType
import com.example.mypasture.bible.domain.BibleCommentaryReviewAction
import com.example.mypasture.bible.domain.BibleCommentaryStatus
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** AI가 생성해야 하는 장별 해설의 고정된 구간 구조입니다. */
data class GeneratedCommentarySection(
    val startVerse: Int,
    val endVerse: Int,
    val title: String,
    val explanation: String,
)

/** AI 출력에서 성경 본문을 직접 인용하지 않고 실제 장·절 위치만 표현합니다. */
data class GeneratedCommentaryReference(
    val bookCode: String,
    val chapter: Int,
    val verse: Int,
    val reason: String,
)

/** 구조화 출력으로 생성된 해설 내용과 실제 토큰 사용량을 전달합니다. */
data class GeneratedBibleCommentary(
    val summary: String,
    val historicalContext: String,
    val sections: List<GeneratedCommentarySection>,
    val keyThemes: List<String>,
    val crossReferences: List<GeneratedCommentaryReference>,
    val reflectionQuestions: List<String>,
    val cautions: List<String>,
    val model: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val estimatedCostUsd: BigDecimal,
)

/** 승인된 출처와 저작권 상태가 확인되어 생성 모델에 제공할 수 있는 배경 메모입니다. */
data class BibleCommentaryEvidence(
    val id: UUID,
    val version: String,
    val bookCode: String,
    val chapter: Int?,
    val evidenceType: BibleCommentaryEvidenceType,
    val title: String,
    val content: String,
    val sourceName: String,
    val sourceUrl: String?,
    val licenseNote: String,
    val status: BibleCommentaryEvidenceStatus,
    val createdBy: UUID,
    val approvedBy: UUID?,
    val approvedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** 평가 모델이 본문 충실성, 근거성, 중립성과 가독성을 구조화해 반환한 결과입니다. */
data class BibleCommentaryEvaluation(
    val passed: Boolean,
    val groundingScore: Int,
    val historicalEvidenceScore: Int,
    val referenceRelevanceScore: Int,
    val theologicalNeutralityScore: Int,
    val clarityScore: Int,
    val fabricatedScripture: Boolean,
    val unsupportedHistoricalClaims: Boolean,
    val issues: List<String>,
    val model: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val estimatedCostUsd: BigDecimal,
) {
    init {
        require(
            listOf(
                groundingScore,
                historicalEvidenceScore,
                referenceRelevanceScore,
                theologicalNeutralityScore,
                clarityScore,
            ).all { it in 1..5 },
        ) { "해설 평가 점수는 1부터 5 사이여야 합니다." }
    }

    val minimumScore: Int
        get() = minOf(groundingScore, historicalEvidenceScore, referenceRelevanceScore, theologicalNeutralityScore, clarityScore)
}

/** 평가 모델의 선언뿐 아니라 위험 플래그와 모든 항목의 최소 점수를 함께 확인합니다. */
fun BibleCommentaryEvaluation.passes(threshold: Int): Boolean {
    require(threshold in 1..5) { "해설 평가 기준은 1부터 5 사이여야 합니다." }
    return passed && minimumScore >= threshold && !fabricatedScripture && !unsupportedHistoricalClaims
}

/** 생성·평가·재작성 과정이 끝난 최종 해설 후보와 누적 평가 사용량입니다. */
data class EvaluatedBibleCommentary(
    val commentary: GeneratedBibleCommentary,
    val evaluation: BibleCommentaryEvaluation,
    val revisionCount: Int,
)

/** 관리자가 AI 초안의 본문을 수정할 때 전달하는 검수용 콘텐츠입니다. */
data class BibleCommentaryDraftEdit(
    val summary: String,
    val historicalContext: String,
    val sections: List<GeneratedCommentarySection>,
    val keyThemes: List<String>,
    val crossReferences: List<GeneratedCommentaryReference>,
    val reflectionQuestions: List<String>,
    val cautions: List<String>,
    val reviewNotes: String,
)

/** 관리자가 출처와 사용권을 확인한 배경 메모를 등록할 때 사용하는 명령입니다. */
data class CreateBibleCommentaryEvidenceCommand(
    val version: String,
    val bookCode: String,
    val chapter: Int?,
    val evidenceType: BibleCommentaryEvidenceType,
    val title: String,
    val content: String,
    val sourceName: String,
    val sourceUrl: String?,
    val licenseNote: String,
)

/** 공개 API와 관리자 화면이 함께 사용하는 검수된 장별 해설 application model입니다. */
data class BibleChapterCommentary(
    val id: UUID,
    val version: String,
    val bookCode: String,
    val bookName: String,
    val chapter: Int,
    val revision: Int,
    val status: BibleCommentaryStatus,
    val summary: String,
    val historicalContext: String,
    val sections: List<GeneratedCommentarySection>,
    val keyThemes: List<String>,
    val crossReferences: List<GeneratedCommentaryReference>,
    val reflectionQuestions: List<String>,
    val cautions: List<String>,
    val model: String,
    val qualityScore: Int?,
    val evaluationStatus: BibleCommentaryEvaluationStatus,
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
    val publishedAt: Instant?,
    val createdAt: Instant,
)

/** 책 전체 생성 요청에서 실제 등록·건너뜀·실패 장 수를 알려주는 결과입니다. */
data class BibleCommentaryBookGenerationResult(
    val version: String,
    val bookCode: String,
    val queuedChapters: List<Int>,
    val skippedChapters: List<Int>,
    val unavailableChapters: List<Int>,
)

/** 관리자 검수 이력을 화면에 노출하기 위한 application model입니다. */
data class BibleCommentaryReviewEvent(
    val id: Long,
    val commentaryId: UUID,
    val previousCommentaryId: UUID?,
    val action: BibleCommentaryReviewAction,
    val reviewerId: UUID,
    val notes: String,
    val createdAt: Instant,
)

/** 관리자에게 생성 작업 진행 상태와 실패 원인을 보여주는 application model입니다. */
data class BibleCommentaryGenerationJob(
    val id: UUID,
    val version: String,
    val bookCode: String,
    val chapter: Int,
    val status: BibleCommentaryJobStatus,
    val attemptCount: Int,
    val errorMessage: String?,
    val commentaryId: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
)
