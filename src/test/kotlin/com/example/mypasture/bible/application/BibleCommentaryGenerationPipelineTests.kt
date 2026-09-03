package com.example.mypasture.bible.application

import com.example.mypasture.ai.domain.AiReasoningEffort
import com.example.mypasture.ai.domain.AiRuntimeConfigSnapshot
import com.example.mypasture.ai.domain.AiUseCase
import com.example.mypasture.bible.domain.BibleCatalog
import com.example.mypasture.bible.domain.BibleChapter
import com.example.mypasture.bible.domain.BibleChapterContentStatus
import com.example.mypasture.bible.domain.BibleVerse
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 장별 해설의 자동 재작성 횟수와 생성·평가 비용 누적을 검증합니다. */
class BibleCommentaryGenerationPipelineTests {
    @Test
    fun `failed candidate is revised once and all usage is accumulated`() = runTest {
        val receivedRevisionIssues = mutableListOf<List<String>>()
        var generationCount = 0
        val generator = BibleCommentaryGeneratorPort { _, _, _, _, issues ->
            receivedRevisionIssues += issues
            generationCount += 1
            generated(
                promptTokens = if (generationCount == 1) 10 else 30,
                completionTokens = if (generationCount == 1) 20 else 40,
                cost = if (generationCount == 1) "0.01" else "0.02",
            )
        }
        var evaluationCount = 0
        val evaluator = BibleCommentaryEvaluatorPort { _, _, _, _ ->
            evaluationCount += 1
            evaluation(
                passed = evaluationCount == 2,
                promptTokens = if (evaluationCount == 1) 5 else 7,
                completionTokens = if (evaluationCount == 1) 6 else 8,
                cost = if (evaluationCount == 1) "0.003" else "0.004",
            )
        }
        val pipeline = BibleCommentaryGenerationPipeline(generator, evaluator)

        val result = pipeline.generate(chapter(), emptyList(), config())

        assertEquals(1, result.revisionCount)
        assertEquals(40, result.commentary.promptTokens)
        assertEquals(60, result.commentary.completionTokens)
        assertEquals(BigDecimal("0.03"), result.commentary.estimatedCostUsd)
        assertEquals(12, result.evaluation.promptTokens)
        assertEquals(14, result.evaluation.completionTokens)
        assertEquals(BigDecimal("0.007"), result.evaluation.estimatedCostUsd)
        assertTrue(receivedRevisionIssues.first().isEmpty())
        assertEquals(listOf("역사적 설명의 근거가 부족합니다."), receivedRevisionIssues.last())
    }

    private fun chapter(): BibleChapter {
        val book = BibleCatalog.findBook("GEN")!!
        return BibleChapter(
            version = "KOR1910",
            book = book,
            chapter = 1,
            contentStatus = BibleChapterContentStatus.AVAILABLE,
            previous = null,
            next = null,
            verses = listOf(BibleVerse("KOR1910", "GEN", "창세기", 1, 1, "태초에 하나님이 천지를 창조하시니라")),
        )
    }

    private fun config() = AiRuntimeConfigSnapshot(
        id = null,
        useCase = AiUseCase.BIBLE_COMMENTARY,
        version = 0,
        generatorModel = "test-generator",
        evaluatorModel = "test-evaluator",
        generatorReasoningEffort = AiReasoningEffort.LOW,
        evaluatorReasoningEffort = AiReasoningEffort.NONE,
        maxOutputTokens = 1000,
        evaluationMaxOutputTokens = 300,
        evaluationEnabled = true,
        evaluationThreshold = 4,
        maxRevisionCount = 1,
        source = "TEST",
    )

    private fun generated(promptTokens: Int, completionTokens: Int, cost: String) = GeneratedBibleCommentary(
        summary = "하나님이 창조의 주체이심을 보여주는 장별 해설 요약입니다. 충분한 검증 길이를 확보하기 위한 문장입니다.",
        historicalContext = "본문의 문학적 시작과 흐름을 중심으로 설명합니다.",
        sections = listOf(GeneratedCommentarySection(1, 1, "창조의 시작", "하나님이 창조의 주체로 제시됩니다.")),
        keyThemes = listOf("창조"),
        crossReferences = emptyList(),
        reflectionQuestions = listOf("하나님의 창조를 어떻게 묵상할 수 있습니까?"),
        cautions = emptyList(),
        model = "test-generator",
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        estimatedCostUsd = BigDecimal(cost),
    )

    private fun evaluation(passed: Boolean, promptTokens: Int, completionTokens: Int, cost: String) =
        BibleCommentaryEvaluation(
            passed = passed,
            groundingScore = if (passed) 5 else 3,
            historicalEvidenceScore = if (passed) 4 else 2,
            referenceRelevanceScore = 5,
            theologicalNeutralityScore = 4,
            clarityScore = 5,
            fabricatedScripture = false,
            unsupportedHistoricalClaims = !passed,
            issues = if (passed) emptyList() else listOf("역사적 설명의 근거가 부족합니다."),
            model = "test-evaluator",
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            estimatedCostUsd = BigDecimal(cost),
        )
}
