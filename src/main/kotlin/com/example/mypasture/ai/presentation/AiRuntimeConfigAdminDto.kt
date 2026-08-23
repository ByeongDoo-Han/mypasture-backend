package com.example.mypasture.ai.presentation

import com.example.mypasture.ai.application.AiRuntimeConfigCommand
import com.example.mypasture.ai.application.AiRuntimeConfigVersionView
import com.example.mypasture.ai.domain.AiModelDefinition
import com.example.mypasture.ai.domain.AiModelRole
import com.example.mypasture.ai.domain.AiReasoningEffort
import com.example.mypasture.ai.domain.AiRuntimeConfigSnapshot
import com.example.mypasture.ai.domain.AiUseCase
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** 관리자 AI 설정 활성화 요청의 허용 범위를 서버에서도 검증합니다. */
data class ActivateAiRuntimeConfigRequest(
    val operationId: UUID,
    val useCase: AiUseCase = AiUseCase.AI_PASTOR,
    @field:NotBlank val generatorModel: String,
    @field:NotBlank val evaluatorModel: String,
    val generatorReasoningEffort: AiReasoningEffort,
    val evaluatorReasoningEffort: AiReasoningEffort,
    @field:Min(100) @field:Max(2_000) val maxOutputTokens: Int,
    @field:Min(100) @field:Max(500) val evaluationMaxOutputTokens: Int,
    val evaluationEnabled: Boolean,
    @field:Min(1) @field:Max(5) val evaluationThreshold: Int,
    @field:Min(0) @field:Max(1) val maxRevisionCount: Int,
    @field:Size(min = 5, max = 500) val reason: String,
) {
    fun toCommand() = AiRuntimeConfigCommand(
        useCase = useCase,
        generatorModel = generatorModel,
        evaluatorModel = evaluatorModel,
        generatorReasoningEffort = generatorReasoningEffort,
        evaluatorReasoningEffort = evaluatorReasoningEffort,
        maxOutputTokens = maxOutputTokens,
        evaluationMaxOutputTokens = evaluationMaxOutputTokens,
        evaluationEnabled = evaluationEnabled,
        evaluationThreshold = evaluationThreshold,
        maxRevisionCount = maxRevisionCount,
        reason = reason,
    )
}

/** 과거 설정을 새 버전으로 복원하는 멱등 관리자 요청입니다. */
data class RollbackAiRuntimeConfigRequest(
    val operationId: UUID,
    val targetVersionId: UUID,
    @field:Size(min = 5, max = 500) val reason: String,
)

data class AiModelDefinitionResponse(
    val modelId: String,
    val displayName: String,
    val roles: Set<AiModelRole>,
    val inputCostPerMillionUsd: BigDecimal,
    val outputCostPerMillionUsd: BigDecimal,
    val streamingSupported: Boolean,
    val structuredOutputSupported: Boolean,
)

data class AiRuntimeConfigResponse(
    val id: UUID?,
    val useCase: AiUseCase,
    val version: Long,
    val generatorModel: String,
    val evaluatorModel: String,
    val generatorReasoningEffort: AiReasoningEffort,
    val evaluatorReasoningEffort: AiReasoningEffort,
    val maxOutputTokens: Int,
    val evaluationMaxOutputTokens: Int,
    val evaluationEnabled: Boolean,
    val evaluationThreshold: Int,
    val maxRevisionCount: Int,
    val source: String,
)

data class AiRuntimeConfigVersionResponse(
    val config: AiRuntimeConfigResponse,
    val active: Boolean,
    val previousVersionId: UUID?,
    val createdBy: UUID?,
    val reason: String,
    val createdAt: Instant,
)

data class AiRuntimeConfigOverviewResponse(
    val current: AiRuntimeConfigResponse,
    val models: List<AiModelDefinitionResponse>,
    val history: List<AiRuntimeConfigVersionResponse>,
)

fun AiModelDefinition.toResponse() = AiModelDefinitionResponse(
    modelId, displayName, roles, inputCostPerMillionUsd, outputCostPerMillionUsd,
    streamingSupported, structuredOutputSupported,
)

fun AiRuntimeConfigSnapshot.toResponse() = AiRuntimeConfigResponse(
    id, useCase, version, generatorModel, evaluatorModel, generatorReasoningEffort,
    evaluatorReasoningEffort, maxOutputTokens, evaluationMaxOutputTokens,
    evaluationEnabled, evaluationThreshold, maxRevisionCount, source,
)

fun AiRuntimeConfigVersionView.toResponse() = AiRuntimeConfigVersionResponse(
    config.toResponse(), active, previousVersionId, createdBy, reason, createdAt,
)
