package com.example.mypasture.ai.application

import com.example.mypasture.ai.domain.*
import org.springframework.stereotype.Service
import java.math.BigDecimal

/** 지원 범위 밖 질문에 말씀과 기도를 생성하지 않도록 서버에서 차단합니다. */
class AiQuestionOutOfScopeException : RuntimeException("AI 목자는 성경·기독교·종교와 신앙 상담을 도와드려요. 이 질문에는 성경 구절이나 기도문을 추천하지 않아요.")

/** 질문 목적이 모호하면 사용자가 원하는 도움을 구체화하도록 안내합니다. */
class AiQuestionScopeUnclearException : RuntimeException("어떤 도움을 원하시나요? 성경·신앙에 관한 궁금증이나 나누고 싶은 마음을 조금 더 구체적으로 적어 주세요.")

/** 잘못된 판단 출력도 이미 발생한 제공자 사용량을 실패 기록에 전달합니다. */
class AiQuestionScopeProviderException(
    val assessment: AiQuestionScopeAssessment,
    cause: Throwable? = null,
) : AiProviderException("AI 질문 관련성을 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.", cause)

/** 모델의 구조화 판단을 서버 정책으로 검증하는 관련성 관문입니다. */
@Service
class AiQuestionScopeService(private val port: AiQuestionScopePort) {
    suspend fun assess(question: String, context: AiConversationContext?, config: AiRuntimeConfigSnapshot) =
        port.assess(question, context, config)

    fun requireSupported(assessment: AiQuestionScopeAssessment) {
        if (!assessment.validated) throw AiQuestionScopeProviderException(assessment)
        if (assessment.uncertain || assessment.scope == AiQuestionScope.UNCLEAR) throw AiQuestionScopeUnclearException()
        if (assessment.scope == AiQuestionScope.UNRELATED) throw AiQuestionOutOfScopeException()
    }
}

/** 관련성 비용만 누적하며 답변 품질 점수와 평가 결과는 그대로 보존합니다. */
internal fun GroundedAnswer.withScopeUsage(scope: AiQuestionScopeAssessment?) = if (scope == null) this else copy(
    evaluatorModel = evaluatorModel ?: scope.evaluatorModel,
    evaluationPromptTokens = evaluationPromptTokens + scope.promptTokens,
    evaluationCompletionTokens = evaluationCompletionTokens + scope.completionTokens,
    evaluationCostUsd = evaluationCostUsd.add(scope.estimatedCostUsd),
    estimatedCostUsd = estimatedCostUsd.add(scope.estimatedCostUsd),
)

/** 답변이 생성되지 않은 실패에서도 평가·검색 비용을 기존 사용량 구조로 전달합니다. */
internal fun consumedAiUsage(
    answer: GroundedAnswer?,
    scope: AiQuestionScopeAssessment?,
    retrieval: BibleRetrievalResult?,
): GroundedAnswer? {
    if (answer == null && scope == null && retrieval == null) return null
    val usage = answer ?: GroundedAnswer(null, answer = "", citations = emptyList(), promptTokens = 0,
        completionTokens = 0, estimatedCostUsd = BigDecimal.ZERO, cacheHit = false)
    return usage.withScopeUsage(scope).let {
        if (retrieval == null) it else it.copy(
            estimatedCostUsd = it.estimatedCostUsd.add(retrieval.embeddingCostUsd),
            embeddingTokens = retrieval.embeddingTokens,
            embeddingCostUsd = retrieval.embeddingCostUsd,
            retrievalStrategy = retrieval.strategy,
        )
    }
}

/** 복구 조회에도 일반/SSE와 동일한 관련성 오류 코드를 저장합니다. */
internal fun Throwable.aiFailureCode(fallback: String): String = when (this) {
    is AiQuestionOutOfScopeException -> "AI_QUESTION_OUT_OF_SCOPE"
    is AiQuestionScopeUnclearException -> "AI_QUESTION_SCOPE_UNCLEAR"
    else -> this::class.simpleName ?: fallback
}
