package com.example.mypasture.bible.application

import com.example.mypasture.ai.domain.AiRuntimeConfigSnapshot
import com.example.mypasture.bible.domain.BibleChapter
import org.springframework.stereotype.Service
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty

/** 장별 해설을 생성하고 평가 지적에 따라 제한 횟수만 재작성하는 application pipeline입니다. */
@Service
@ConditionalOnProperty(name = ["app.ai.commentary-generation-enabled"], havingValue = "true")
class BibleCommentaryGenerationPipeline(
    private val generator: BibleCommentaryGeneratorPort,
    private val evaluator: BibleCommentaryEvaluatorPort,
) {
    suspend fun generate(
        chapter: BibleChapter,
        evidence: List<BibleCommentaryEvidence>,
        config: AiRuntimeConfigSnapshot,
    ): EvaluatedBibleCommentary {
        var generated = generator.generate(chapter, evidence, config, null, emptyList())
        var evaluation = evaluator.evaluate(chapter, evidence, generated, config)
        var revisionCount = 0

        while (!evaluation.passes(config.evaluationThreshold) && revisionCount < config.maxRevisionCount) {
            val previousGeneration = generated
            val previousEvaluation = evaluation
            revisionCount += 1
            val revised = generator.generate(chapter, evidence, config, previousGeneration, evaluation.issues)
            generated = revised.copy(
                promptTokens = previousGeneration.promptTokens + revised.promptTokens,
                completionTokens = previousGeneration.completionTokens + revised.completionTokens,
                estimatedCostUsd = previousGeneration.estimatedCostUsd.add(revised.estimatedCostUsd),
            )
            val reevaluated = evaluator.evaluate(chapter, evidence, generated, config)
            evaluation = reevaluated.copy(
                promptTokens = previousEvaluation.promptTokens + reevaluated.promptTokens,
                completionTokens = previousEvaluation.completionTokens + reevaluated.completionTokens,
                estimatedCostUsd = previousEvaluation.estimatedCostUsd.add(reevaluated.estimatedCostUsd),
            )
        }
        return EvaluatedBibleCommentary(generated, evaluation, revisionCount)
    }
}
