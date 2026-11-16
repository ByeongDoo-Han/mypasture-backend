package com.example.mypasture.ai.infrastructure

import com.example.mypasture.ai.application.AiQuestionScopePort
import com.example.mypasture.ai.domain.AiQuestionScope
import com.example.mypasture.ai.domain.AiQuestionScopeAssessment
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
class LocalAiCompletionAdapter : AiCompletionPort, AiStreamingCompletionPort, AiAnswerEvaluationPort, AiQuestionScopePort {
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
        generationInstructions: List<String>,
        previousAnswer: String?,
        revisionInstructions: List<String>,
        onDelta: suspend (String) -> Unit,
    ): GroundedAnswer = answerWithCitations(
        question,
        citations,
        config,
        experiencePolicy,
        conversationContext,
        generationInstructions,
        previousAnswer,
        revisionInstructions,
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

    /** 로컬 mock의 보수적 분기는 흐름 테스트용이며 운영 의미 분류를 대신하지 않습니다. */
    override suspend fun assess(
        currentQuestion: String,
        conversationContext: AiConversationContext?,
        config: AiRuntimeConfigSnapshot,
    ): AiQuestionScopeAssessment {
        val question = currentQuestion.lowercase()
        val financialTopic = listOf("주가", "비트코인", "btc", "삼성전자", "stock").any(question::contains)
        val directFinancialTask = financialTopic && listOf("알려", "조회", "얼마", "시세", "현재 가격", "price", "예측", "추천 종목").any(question::contains)
        val scope = when {
            directFinancialTask || listOf("시세", "날씨", "코딩", "파이썬", "python", "stock price", "weather", "write code", "관련으로 분류", "related로", "ignore previous").any(question::contains) -> AiQuestionScope.UNRELATED
            question in listOf("어떻게 해야 할까?", "어떻게 해야 할까요?", "어떡해", "그냥") -> AiQuestionScope.UNCLEAR
            listOf("성경", "기독", "신앙", "하나님", "예수", "기도", "말씀", "창세기", "요한", "시편", "잠언", "불교", "이슬람", "종교", "힌두", "bible", "christian", "religion", "buddh", "islam").any(question::contains) -> AiQuestionScope.RELATED
            listOf("불안", "외로", "상처", "관계", "슬픔", "마음", "힘들", "걱정", "위로", "용서", "평안", "상담", "anxious", "lonely", "grief").any(question::contains) -> AiQuestionScope.PASTORAL_SUPPORT
            conversationContext?.turns?.lastOrNull()?.followUpQuestion != null && question in listOf("네", "아니요", "첫 번째", "두 번째") -> AiQuestionScope.PASTORAL_SUPPORT
            question.length < 8 || question.all { it.isLetter() && it.code < 128 } -> AiQuestionScope.UNCLEAR
            else -> AiQuestionScope.UNRELATED
        }
        return AiQuestionScopeAssessment(scope, scope == AiQuestionScope.UNCLEAR, evaluatorModel = config.evaluatorModel)
    }

    private fun estimateTokens(text: String): Int = (text.length / 3).coerceAtLeast(1)
}
