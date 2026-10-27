package com.example.mypasture.ai.application

import com.example.mypasture.ai.domain.AiAnswerEvaluation
import com.example.mypasture.ai.domain.AiRuntimeConfigSnapshot
import com.example.mypasture.ai.domain.AiAssistantExperiencePolicy
import com.example.mypasture.ai.domain.AiConversationContext
import com.example.mypasture.ai.domain.AiInteractionMode
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
        experiencePolicy: AiAssistantExperiencePolicy = DEFAULT_EXPERIENCE_POLICY,
        conversationContext: AiConversationContext? = null,
        generationInstructions: List<String> = emptyList(),
        onReview: () -> Unit = {},
    ): GroundedAnswer = generateInternal(question, citations, config, onReview) { previousAnswer, revisionInstructions ->
        completionPort.answerWithCitations(
            question, citations, config, experiencePolicy, conversationContext,
            generationInstructions, previousAnswer, revisionInstructions,
        )
    }

    /** 검증 가능한 생성 초안을 전달하고 재작성 시 화면 초안을 초기화합니다. */
    suspend fun generateStreaming(
        question: String,
        citations: List<BibleCitation>,
        config: AiRuntimeConfigSnapshot,
        experiencePolicy: AiAssistantExperiencePolicy,
        conversationContext: AiConversationContext?,
        streamingPort: AiStreamingCompletionPort,
        onDelta: suspend (String) -> Unit,
        onReset: suspend () -> Unit,
        onReview: () -> Unit = {},
    ): GroundedAnswer = generateInternal(question, citations, config, onReview) { previousAnswer, revisionInstructions ->
        if (previousAnswer != null) onReset()
        streamingPort.answerWithCitations(
            question, citations, config, experiencePolicy, conversationContext,
            emptyList(), previousAnswer, revisionInstructions, onDelta,
        )
    }

    private suspend fun generateInternal(
        question: String,
        citations: List<BibleCitation>,
        config: AiRuntimeConfigSnapshot,
        onReview: () -> Unit,
        generateCandidate: suspend (String?, List<String>) -> GroundedAnswer,
    ): GroundedAnswer {
        var generated = generateCandidate(null, emptyList())
        if (!config.evaluationEnabled) return generated.withRuntimeMetadata(config)

        var evaluationPromptTokens = 0
        var evaluationCompletionTokens = 0
        var evaluationCost = BigDecimal.ZERO
        var revisionCount = 0

        while (true) {
            onReview()
            val evaluation = evaluationPort.evaluate(question, citations, generated.reviewableContent(), config)
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
            val revised = generateCandidate(
                previous.answer,
                evaluation.issues.ifEmpty { listOf("모든 평가 항목을 기준 이상으로 개선하세요.") },
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

    private fun GroundedAnswer.reviewableContent(): String = buildString {
        append(answer)
        prayer?.let { append("\n추천 기도문: ").append(it.content) }
        followUpQuestion?.let { append("\n후속 질문: ").append(it.question) }
    }

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

    companion object {
        private val DEFAULT_EXPERIENCE_POLICY = AiAssistantExperiencePolicy(
            interactionMode = AiInteractionMode.ONE_SHOT,
            scriptureCount = 3,
            includePrayer = true,
            allowFollowUp = false,
            maxContextTurns = 0,
        )
    }
}
