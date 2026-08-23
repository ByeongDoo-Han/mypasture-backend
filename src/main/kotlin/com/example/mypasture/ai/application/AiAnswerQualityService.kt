package com.example.mypasture.ai.application

import com.example.mypasture.ai.domain.AiAnswerEvaluation
import com.example.mypasture.ai.domain.AiRuntimeConfigSnapshot
import com.example.mypasture.ai.domain.BibleCitation
import com.example.mypasture.ai.domain.GroundedAnswer
import org.springframework.stereotype.Service
import java.math.BigDecimal

class AiAnswerRejectedException(
    val rejectedAnswer: GroundedAnswer,
) : RuntimeException("AI 답변이 성경 근거 품질 기준을 충족하지 못했습니다.")

/** 생성 모델의 답변을 평가하고 기준 미달일 때 한 번만 수정해 검증된 최종 답변만 반환합니다. */
@Service
class AiAnswerQualityService(
    private val completionPort: AiCompletionPort,
    private val evaluationPort: AiAnswerEvaluationPort,
) {
    suspend fun generate(
        question: String,
        citations: List<BibleCitation>,
        config: AiRuntimeConfigSnapshot,
        onReview: () -> Unit = {},
    ): GroundedAnswer {
        var generated = completionPort.answerWithCitations(question, citations, config)
        if (!config.evaluationEnabled) return generated.withRuntimeMetadata(config)

        var evaluationPromptTokens = 0
        var evaluationCompletionTokens = 0
        var evaluationCost = BigDecimal.ZERO
        var revisionCount = 0

        while (true) {
            onReview()
            val evaluation = evaluationPort.evaluate(question, citations, generated.answer, config)
            evaluationPromptTokens += evaluation.promptTokens
            evaluationCompletionTokens += evaluation.completionTokens
            evaluationCost = evaluationCost.add(evaluation.estimatedCostUsd)
            val passed = passes(evaluation, config.evaluationThreshold)

            val reviewed = generated.withEvaluation(
                config = config,
                evaluation = evaluation,
                passed = passed,
                evaluationPromptTokens = evaluationPromptTokens,
                evaluationCompletionTokens = evaluationCompletionTokens,
                evaluationCost = evaluationCost,
                revisionCount = revisionCount,
            )
            if (passed) return reviewed

            if (revisionCount >= config.maxRevisionCount) throw AiAnswerRejectedException(reviewed)

            val previous = generated
            revisionCount++
            val revised = completionPort.answerWithCitations(
                question = question,
                citations = citations,
                config = config,
                previousAnswer = previous.answer,
                revisionInstructions = evaluation.issues.ifEmpty { listOf("모든 평가 항목을 기준 이상으로 개선하세요.") },
            )
            generated = revised.copy(
                promptTokens = previous.promptTokens + revised.promptTokens,
                completionTokens = previous.completionTokens + revised.completionTokens,
                estimatedCostUsd = previous.estimatedCostUsd.add(revised.estimatedCostUsd),
            )
        }
    }

    private fun passes(evaluation: AiAnswerEvaluation, threshold: Int): Boolean =
        evaluation.passed &&
            !evaluation.fabricatedScripture &&
            !evaluation.uncertain &&
            listOf(
                evaluation.groundingScore,
                evaluation.citationScore,
                evaluation.safetyScore,
                evaluation.relevanceScore,
                evaluation.pastoralToneScore,
            ).all { it >= threshold }

    private fun GroundedAnswer.withRuntimeMetadata(config: AiRuntimeConfigSnapshot) = copy(
        modelConfigVersionId = config.id,
        generatorModel = config.generatorModel,
        evaluationPassed = null,
    )

    private fun GroundedAnswer.withEvaluation(
        config: AiRuntimeConfigSnapshot,
        evaluation: AiAnswerEvaluation,
        passed: Boolean,
        evaluationPromptTokens: Int,
        evaluationCompletionTokens: Int,
        evaluationCost: BigDecimal,
        revisionCount: Int,
    ) = copy(
        estimatedCostUsd = estimatedCostUsd.add(evaluationCost),
        modelConfigVersionId = config.id,
        generatorModel = config.generatorModel,
        evaluatorModel = config.evaluatorModel,
        evaluationPromptTokens = evaluationPromptTokens,
        evaluationCompletionTokens = evaluationCompletionTokens,
        evaluationCostUsd = evaluationCost,
        evaluationPassed = passed,
        evaluationGroundingScore = evaluation.groundingScore,
        evaluationCitationScore = evaluation.citationScore,
        evaluationSafetyScore = evaluation.safetyScore,
        evaluationRelevanceScore = evaluation.relevanceScore,
        evaluationPastoralToneScore = evaluation.pastoralToneScore,
        evaluationIssues = evaluation.issues,
        revisionCount = revisionCount,
    )
}
