package com.example.mypasture.ai.domain

import java.math.BigDecimal
import java.util.UUID

/** 관리자가 독립적으로 운영할 수 있는 AI 사용 목적입니다. */
enum class AiUseCase {
    AI_PASTOR,
    DAILY_WORD,
}

/** OpenAI 모델에 전달할 수 있는 검증된 추론 강도입니다. */
enum class AiReasoningEffort {
    NONE,
    LOW,
    MEDIUM,
    HIGH,
    XHIGH,
    MAX,
}

/** 모델이 담당할 수 있는 생성·평가 역할입니다. */
enum class AiModelRole {
    GENERATOR,
    EVALUATOR,
}

/** 서버가 허용하는 모델의 기능과 가격 스냅샷입니다. 비밀키는 포함하지 않습니다. */
data class AiModelDefinition(
    val modelId: String,
    val displayName: String,
    val roles: Set<AiModelRole>,
    val inputCostPerMillionUsd: BigDecimal,
    val outputCostPerMillionUsd: BigDecimal,
    val streamingSupported: Boolean = true,
    val structuredOutputSupported: Boolean = true,
)

/** 관리자 입력으로 임의 모델명이 주입되지 않도록 중앙에서 관리하는 허용 목록입니다. */
object AiModelCatalog {
    val models: List<AiModelDefinition> = listOf(
        model("gpt-5.6-terra", "GPT-5.6 Terra", setOf(AiModelRole.GENERATOR, AiModelRole.EVALUATOR), "2.00", "12.00"),
        model("gpt-5.6-luna", "GPT-5.6 Luna", setOf(AiModelRole.EVALUATOR), "0.20", "1.20"),
        model("gpt-5.6-sol", "GPT-5.6 Sol", setOf(AiModelRole.GENERATOR, AiModelRole.EVALUATOR), "4.00", "20.00"),
        model("gpt-5.4-mini", "GPT-5.4 mini", setOf(AiModelRole.GENERATOR, AiModelRole.EVALUATOR), "0.75", "4.50"),
        model("gpt-5.4-nano", "GPT-5.4 nano", setOf(AiModelRole.EVALUATOR), "0.20", "1.25"),
    )

    fun require(modelId: String, role: AiModelRole): AiModelDefinition =
        models.firstOrNull { it.modelId == modelId && role in it.roles }
            ?: throw IllegalArgumentException("Model '$modelId' is not allowed for $role.")

    fun pricing(modelId: String): AiModelDefinition? = models.firstOrNull { it.modelId == modelId }

    private fun model(
        id: String,
        name: String,
        roles: Set<AiModelRole>,
        input: String,
        output: String,
    ) = AiModelDefinition(id, name, roles, BigDecimal(input), BigDecimal(output))
}

/** 한 AI 요청이 끝날 때까지 변경되지 않는 생성·평가 설정 스냅샷입니다. */
data class AiRuntimeConfigSnapshot(
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
) {
    val cacheScope: String
        get() = id?.toString() ?: "environment:$generatorModel:$evaluatorModel:$evaluationEnabled:$evaluationThreshold:$maxRevisionCount"
}

/** 평가 모델이 생성 답변을 정해진 기준으로 채점한 구조화 결과입니다. */
data class AiAnswerEvaluation(
    val passed: Boolean,
    val groundingScore: Int,
    val citationScore: Int,
    val safetyScore: Int,
    val relevanceScore: Int,
    val pastoralToneScore: Int,
    val fabricatedScripture: Boolean,
    val uncertain: Boolean,
    val issues: List<String>,
    val promptTokens: Int,
    val completionTokens: Int,
    val estimatedCostUsd: BigDecimal,
    val evaluatorModel: String,
)
