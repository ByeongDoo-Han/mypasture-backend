package com.example.mypasture.ai.infrastructure

import com.example.mypasture.ai.application.AiAnswerEvaluationPort
import com.example.mypasture.ai.application.AiCompletionPort
import com.example.mypasture.ai.application.AiStreamingCompletionPort
import com.example.mypasture.ai.domain.AiAnswerEvaluation
import com.example.mypasture.ai.domain.AiAssistantExperiencePolicy
import com.example.mypasture.ai.domain.AiConversationContext
import com.example.mypasture.ai.domain.AiFollowUpQuestion
import com.example.mypasture.ai.domain.AiRecommendedPrayer
import com.example.mypasture.ai.domain.AiRuntimeConfigSnapshot
import com.example.mypasture.ai.domain.BibleCitation
import com.example.mypasture.ai.domain.GroundedAnswer
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.math.BigDecimal

/** 개발 환경에서 외부 비용 없이 RAG와 화면 흐름을 검증하는 성경 근거 응답기입니다. */
@Component
@ConditionalOnProperty(prefix = "app.ai", name = ["mock-enabled"], havingValue = "true")
class LocalAiCompletionAdapter : AiCompletionPort, AiStreamingCompletionPort, AiAnswerEvaluationPort {
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
        val references = citations.take(3).joinToString(", ") { "${it.book} ${it.chapter}:${it.verse} [${it.verseId}]" }
        val answer =
            "질문해 주신 마음을 $references 말씀에 비추어 함께 살펴볼 수 있습니다. " +
                "이 구절들을 천천히 읽으며 지금 붙들고 싶은 문장을 한 가지 정해 보세요. " +
                "그 문장을 바탕으로 솔직하게 기도하고, 오늘 실천할 수 있는 작은 한 걸음을 적어보는 것도 좋습니다."
        return GroundedAnswer(
            conversationId = null,
            answer = answer,
            citations = citations,
            promptTokens = estimateTokens(question + citations.joinToString { it.text }),
            completionTokens = estimateTokens(answer),
            estimatedCostUsd = BigDecimal.ZERO,
            cacheHit = false,
            generatorModel = config.generatorModel,
            prayer = AiRecommendedPrayer(
                title = "말씀을 붙드는 기도",
                content = "하나님, 오늘 주신 말씀을 제 상황에 비추어 보고 작은 한 걸음을 순종하게 해 주세요. 아멘.",
            ),
            followUpQuestion = if (experiencePolicy.allowFollowUp) {
                AiFollowUpQuestion(
                    question = "지금 가장 먼저 말씀의 도움을 받고 싶은 상황은 무엇인가요?",
                    options = listOf("마음의 평안", "관계의 회복", "결정과 방향", "믿음의 성장"),
                )
            } else null,
            interactionMode = experiencePolicy.interactionMode,
        )
    }

    /** 로컬 개발에서도 클라이언트가 SSE 누적 로직을 검증할 수 있도록 작은 조각으로 전달합니다. */
    override suspend fun answerWithCitations(
        question: String,
        citations: List<BibleCitation>,
        config: AiRuntimeConfigSnapshot,
        experiencePolicy: AiAssistantExperiencePolicy,
        conversationContext: AiConversationContext?,
        onDelta: suspend (String) -> Unit,
    ): GroundedAnswer = answerWithCitations(
        question,
        citations,
        config,
        experiencePolicy,
        conversationContext,
    ).also { answer ->
        answer.answer.chunked(12).forEach { onDelta(it) }
    }

    override suspend fun evaluate(
        question: String,
        citations: List<BibleCitation>,
        answer: String,
        config: AiRuntimeConfigSnapshot,
    ): AiAnswerEvaluation = AiAnswerEvaluation(
        passed = true,
        groundingScore = 5,
        citationScore = 5,
        safetyScore = 5,
        relevanceScore = 5,
        pastoralToneScore = 5,
        fabricatedScripture = false,
        uncertain = false,
        issues = emptyList(),
        promptTokens = estimateTokens(question + answer),
        completionTokens = 20,
        estimatedCostUsd = BigDecimal.ZERO,
        evaluatorModel = config.evaluatorModel,
    )

    private fun estimateTokens(text: String): Int = (text.length / 3).coerceAtLeast(1)
}
