package com.example.mypasture.ai

import com.example.mypasture.ai.application.AiAnswerEvaluationPort
import com.example.mypasture.ai.application.AiAnswerQualityService
import com.example.mypasture.ai.application.AiAnswerRejectedException
import com.example.mypasture.ai.application.AiCompletionPort
import com.example.mypasture.ai.application.AiStreamingCompletionPort
import com.example.mypasture.ai.domain.AiAnswerEvaluation
import com.example.mypasture.ai.domain.AiAssistantExperiencePolicy
import com.example.mypasture.ai.domain.AiConversationContext
import com.example.mypasture.ai.domain.AiInteractionMode
import com.example.mypasture.ai.domain.AiReasoningEffort
import com.example.mypasture.ai.domain.AiRuntimeConfigSnapshot
import com.example.mypasture.ai.domain.AiUseCase
import com.example.mypasture.ai.domain.BibleCitation
import com.example.mypasture.ai.domain.GroundedAnswer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** 생성 답변이 평가를 통과하거나 한 번 수정된 뒤에만 외부로 반환되는지 검증합니다. */
class AiAnswerQualityServiceTests {
    @Test
    fun `streams first draft and resets it before a revision`() = runTest {
        val completion = FakeCompletion(listOf("근거: [42] 첫 초안", "근거: [42] 수정 답변"))
        val evaluator = FakeEvaluator(listOf(evaluation(passed = false), evaluation(passed = true)))
        val events = mutableListOf<String>()

        val result = AiAnswerQualityService(completion, evaluator).generateStreaming(
            "질문", listOf(citation()), config(), experiencePolicy(), null, completion,
            onDelta = { events += "delta:$it" },
            onReset = { events += "reset" },
        )

        assertEquals(listOf("delta:근거: [42] 첫 초안", "reset", "delta:근거: [42] 수정 답변"), events)
        assertEquals("근거: [42] 수정 답변", result.answer)
        assertEquals(1, result.revisionCount)
    }

    @Test
    fun `returns first answer when every evaluation criterion passes`() = runTest {
        val completion = FakeCompletion(listOf("근거: [42] 첫 답변"))
        val evaluator = FakeEvaluator(listOf(evaluation(passed = true)))

        val answer = AiAnswerQualityService(completion, evaluator).generate("질문", listOf(citation()), config())

        assertEquals("근거: [42] 첫 답변", answer.answer)
        assertEquals(0, answer.revisionCount)
        assertEquals(true, answer.evaluationPassed)
        assertEquals("gpt-5.6-luna", answer.evaluatorModel)
        assertEquals(1, completion.calls)
    }

    @Test
    fun `revises once and aggregates generator and evaluator cost`() = runTest {
        val completion = FakeCompletion(listOf("근거가 약한 답변", "근거: [42] 수정 답변"))
        val evaluator = FakeEvaluator(
            listOf(
                evaluation(passed = false, grounding = 2, issues = listOf("성경 근거를 분명히 연결하세요.")),
                evaluation(passed = true),
            ),
        )

        val answer = AiAnswerQualityService(completion, evaluator).generate("질문", listOf(citation()), config())

        assertEquals("근거: [42] 수정 답변", answer.answer)
        assertEquals(1, answer.revisionCount)
        assertEquals(20, answer.promptTokens)
        assertEquals(12, answer.completionTokens)
        assertEquals(14, answer.evaluationPromptTokens)
        assertEquals(BigDecimal("0.06"), answer.estimatedCostUsd)
        assertEquals(listOf("성경 근거를 분명히 연결하세요."), completion.lastRevisionInstructions)
    }

    @Test
    fun `rejects answer when revised result still fabricates scripture`() = runTest {
        val completion = FakeCompletion(listOf("첫 답변", "수정 답변"))
        val evaluator = FakeEvaluator(
            listOf(
                evaluation(passed = false, grounding = 2),
                evaluation(passed = true, fabricated = true),
            ),
        )

        val error = assertThrows(AiAnswerRejectedException::class.java) {
            kotlinx.coroutines.runBlocking {
                AiAnswerQualityService(completion, evaluator).generate("질문", listOf(citation()), config())
            }
        }
        assertEquals(2, completion.calls)
        assertEquals(false, error.rejectedAnswer.evaluationPassed)
        assertEquals(20, error.rejectedAnswer.promptTokens)
        assertEquals(14, error.rejectedAnswer.evaluationPromptTokens)
        assertEquals(BigDecimal("0.06"), error.rejectedAnswer.estimatedCostUsd)
    }

    private class FakeCompletion(private val answers: List<String>) : AiCompletionPort, AiStreamingCompletionPort {
        var calls = 0
        var lastRevisionInstructions: List<String> = emptyList()

        override suspend fun answerWithCitations(
            question: String,
            citations: List<BibleCitation>,
            config: AiRuntimeConfigSnapshot,
            experiencePolicy: AiAssistantExperiencePolicy,
            conversationContext: AiConversationContext?,
            generationInstructions: List<String>,
            previousAnswer: String?,
            revisionInstructions: List<String>,
        ): GroundedAnswer {
            lastRevisionInstructions = revisionInstructions
            return GroundedAnswer(
                conversationId = null,
                answer = answers[calls++],
                citations = citations,
                promptTokens = 10,
                completionTokens = 6,
                estimatedCostUsd = BigDecimal("0.02"),
                cacheHit = false,
            )
        }

        override suspend fun answerWithCitations(
            question: String,
            citations: List<BibleCitation>,
            config: AiRuntimeConfigSnapshot,
            experiencePolicy: AiAssistantExperiencePolicy,
            conversationContext: AiConversationContext?,
            generationInstructions: List<String>,
            previousAnswer: String?,
            revisionInstructions: List<String>,
            onDelta: suspend (String) -> Unit,
        ): GroundedAnswer = answerWithCitations(
            question, citations, config, experiencePolicy, conversationContext,
            generationInstructions, previousAnswer, revisionInstructions,
        ).also { onDelta(it.answer) }
    }

    private class FakeEvaluator(private val evaluations: List<AiAnswerEvaluation>) : AiAnswerEvaluationPort {
        private var calls = 0

        override suspend fun evaluate(
            question: String,
            citations: List<BibleCitation>,
            answer: String,
            config: AiRuntimeConfigSnapshot,
        ): AiAnswerEvaluation = evaluations[calls++]
    }

    private fun evaluation(
        passed: Boolean,
        grounding: Int = 5,
        fabricated: Boolean = false,
        issues: List<String> = emptyList(),
    ) = AiAnswerEvaluation(
        passed = passed,
        groundingScore = grounding,
        citationScore = 5,
        safetyScore = 5,
        relevanceScore = 5,
        pastoralToneScore = 5,
        fabricatedScripture = fabricated,
        uncertain = false,
        issues = issues,
        promptTokens = 7,
        completionTokens = 3,
        estimatedCostUsd = BigDecimal("0.01"),
        evaluatorModel = "gpt-5.6-luna",
    )

    private fun experiencePolicy() = AiAssistantExperiencePolicy(
        interactionMode = AiInteractionMode.ONE_SHOT,
        scriptureCount = 3,
        includePrayer = true,
        allowFollowUp = false,
        maxContextTurns = 0,
    )

    private fun config() = AiRuntimeConfigSnapshot(
        id = null,
        useCase = AiUseCase.AI_PASTOR,
        version = 0,
        generatorModel = "gpt-5.6-terra",
        evaluatorModel = "gpt-5.6-luna",
        generatorReasoningEffort = AiReasoningEffort.LOW,
        evaluatorReasoningEffort = AiReasoningEffort.MEDIUM,
        maxOutputTokens = 500,
        evaluationMaxOutputTokens = 250,
        evaluationEnabled = true,
        evaluationThreshold = 4,
        maxRevisionCount = 1,
        source = "TEST",
    )

    private fun citation() = BibleCitation(42, "KOR1910", "시편", 23, 1, "여호와는 나의 목자시니")
}
