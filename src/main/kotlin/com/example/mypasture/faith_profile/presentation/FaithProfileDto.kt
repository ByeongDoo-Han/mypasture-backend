package com.example.mypasture.faith_profile.presentation

import com.example.mypasture.faith_profile.application.FaithResultView
import com.example.mypasture.faith_profile.application.FaithTestDefinition
import com.example.mypasture.faith_profile.domain.FaithAnswerEntity
import com.example.mypasture.faith_profile.domain.FaithAssessmentEntity
import com.example.mypasture.faith_profile.domain.FaithDimensionEntity
import com.example.mypasture.faith_profile.domain.FaithDimensionScoreEntity
import com.example.mypasture.faith_profile.domain.FaithQuestionEntity
import com.example.mypasture.faith_profile.domain.FaithRecommendationEntity
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import java.time.Instant
import java.util.UUID

/** 인증된 사용자의 신앙 성향 테스트 시작 요청 DTO입니다. 사용자 ID는 JWT에서 결정합니다. */
class StartFaithAssessmentRequest

/** 신앙 성향 테스트 문항 답변 요청 DTO입니다. */
data class SubmitFaithAnswerRequest(
    val questionId: UUID,
    @field:Min(1)
    @field:Max(5)
    val score: Int,
)

/** 신앙 성향 테스트 정의 응답 DTO입니다. */
data class FaithTestResponse(
    val testVersionId: UUID,
    val code: String,
    val version: Int,
    val title: String,
    val dimensions: List<FaithDimensionResponse>,
    val questions: List<FaithQuestionResponse>,
)

/** 신앙 성향 테스트 축 응답 DTO입니다. */
data class FaithDimensionResponse(
    val id: UUID,
    val code: String,
    val leftCode: String,
    val leftName: String,
    val rightCode: String,
    val rightName: String,
    val sortOrder: Int,
)

/** 신앙 성향 테스트 문항 응답 DTO입니다. */
data class FaithQuestionResponse(
    val id: UUID,
    val dimensionCode: String,
    val questionText: String,
    val sortOrder: Int,
)

/** 신앙 성향 테스트 세션 시작 응답 DTO입니다. */
data class FaithAssessmentResponse(
    val id: UUID,
    val testVersionId: UUID,
    val status: String,
    val startedAt: Instant,
    val completedAt: Instant?,
)

/** 신앙 성향 테스트 답변 저장 응답 DTO입니다. */
data class FaithAnswerResponse(
    val id: UUID,
    val assessmentId: UUID,
    val questionId: UUID,
    val score: Int,
)

/** 신앙 성향 테스트 완료 결과 응답 DTO입니다. */
data class FaithResultResponse(
    val id: UUID,
    val assessmentId: UUID,
    val typeCode: String,
    val typeName: String,
    val summary: String,
    val strengths: String,
    val cautions: String,
    val growthGuide: String,
    val dimensionScores: List<FaithDimensionScoreResponse>,
    val recommendations: List<FaithRecommendationResponse>,
    val createdAt: Instant,
)

/** 신앙 성향 테스트 축별 점수 응답 DTO입니다. */
data class FaithDimensionScoreResponse(
    val dimensionCode: String,
    val leftCode: String,
    val rightCode: String,
    val leftScore: Int,
    val rightScore: Int,
    val selectedCode: String,
    val confidence: Int,
)

/** 신앙 성향 테스트 유형별 추천 콘텐츠 응답 DTO입니다. */
data class FaithRecommendationResponse(
    val category: String,
    val title: String,
    val content: String,
    val targetFeature: String?,
    val sortOrder: Int,
)

/** 신앙 성향 테스트 API 에러 응답 DTO입니다. */
data class FaithProfileErrorResponse(
    val code: String,
    val message: String,
)

fun FaithTestDefinition.toResponse(): FaithTestResponse =
    FaithTestResponse(
        testVersionId = testVersion.id,
        code = testVersion.code,
        version = testVersion.version,
        title = testVersion.title,
        dimensions = dimensions.map { it.toResponse() },
        questions = questions.map { it.toResponse() },
    )

fun FaithDimensionEntity.toResponse(): FaithDimensionResponse =
    FaithDimensionResponse(id = id, code = code, leftCode = leftCode, leftName = leftName, rightCode = rightCode, rightName = rightName, sortOrder = sortOrder)

fun FaithQuestionEntity.toResponse(): FaithQuestionResponse =
    FaithQuestionResponse(id = id, dimensionCode = dimension.code, questionText = questionText, sortOrder = sortOrder)

fun FaithAssessmentEntity.toResponse(): FaithAssessmentResponse =
    FaithAssessmentResponse(id = id, testVersionId = testVersion.id, status = status.name, startedAt = startedAt, completedAt = completedAt)

fun FaithAnswerEntity.toResponse(): FaithAnswerResponse =
    FaithAnswerResponse(id = id, assessmentId = assessment.id, questionId = question.id, score = score)

fun FaithResultView.toResponse(): FaithResultResponse =
    FaithResultResponse(
        id = result.id,
        assessmentId = result.assessment.id,
        typeCode = result.typeCode,
        typeName = result.typeName,
        summary = result.summary,
        strengths = typeProfile.strengths,
        cautions = typeProfile.cautions,
        growthGuide = typeProfile.growthGuide,
        dimensionScores = dimensionScores.map { it.toResponse() },
        recommendations = recommendations.map { it.toResponse() },
        createdAt = result.createdAt,
    )

private fun FaithDimensionScoreEntity.toResponse(): FaithDimensionScoreResponse =
    FaithDimensionScoreResponse(
        dimensionCode = dimensionCode,
        leftCode = leftCode,
        rightCode = rightCode,
        leftScore = leftScore,
        rightScore = rightScore,
        selectedCode = selectedCode,
        confidence = confidence,
    )

private fun FaithRecommendationEntity.toResponse(): FaithRecommendationResponse =
    FaithRecommendationResponse(category = category.name, title = title, content = content, targetFeature = targetFeature, sortOrder = sortOrder)
