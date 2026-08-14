package com.example.mypasture.bible.application

import com.example.mypasture.bible.domain.BibleCommentaryJobStatus
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
    val promptTokens: Int,
    val completionTokens: Int,
    val estimatedCostUsd: BigDecimal,
    val publishedAt: Instant?,
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
