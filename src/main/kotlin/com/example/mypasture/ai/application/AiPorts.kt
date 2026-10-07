package com.example.mypasture.ai.application

import com.example.mypasture.ai.domain.BibleCitation
import com.example.mypasture.ai.domain.AiAnswerEvaluation
import com.example.mypasture.ai.domain.AiRuntimeConfigSnapshot
import com.example.mypasture.ai.domain.AiAssistantExperiencePolicy
import com.example.mypasture.ai.domain.AiConversationContext
import com.example.mypasture.ai.domain.AiConversationTurn
import com.example.mypasture.ai.domain.AiAnswerFeedback
import com.example.mypasture.ai.domain.AiCompletedTurn
import com.example.mypasture.ai.domain.AiFeedbackRating
import com.example.mypasture.ai.domain.AiFeedbackReason
import com.example.mypasture.ai.domain.AiFeedbackSummary
import com.example.mypasture.ai.domain.AiClientRequest
import com.example.mypasture.ai.domain.AiUsageReservation
import com.example.mypasture.ai.domain.BibleEmbeddingSource
import com.example.mypasture.ai.domain.BibleRetrievalResult
import com.example.mypasture.ai.domain.GroundedAnswer
import com.example.mypasture.ai.domain.TextEmbeddingBatch
import com.example.mypasture.ai.domain.UnderstoodBibleQuestion
import java.util.UUID

/** 성경 구절 검색 구현체를 application layer와 분리하는 RAG 검색 포트입니다. */
interface BibleRetrievalPort {
    suspend fun searchRelevantVerses(
        question: UnderstoodBibleQuestion,
        limit: Int = 10,
    ): BibleRetrievalResult
}

/** 질문과 성경 본문을 같은 embedding model과 차원으로 변환하는 외부 AI 포트입니다. */
interface TextEmbeddingPort {
    suspend fun embed(inputs: List<String>): TextEmbeddingBatch
}

/** PostgreSQL의 성경 embedding index 상태와 멱등 upsert를 application layer에 제공합니다. */
interface BibleEmbeddingStorePort {
    fun isAvailable(): Boolean

    fun tryAcquireIndexLock(): AutoCloseable?

    fun findPendingSources(model: String, dimensions: Int, limit: Int): List<BibleEmbeddingSource>

    fun upsert(model: String, dimensions: Int, sources: List<BibleEmbeddingSource>, vectors: List<FloatArray>)

    fun countTotalVerses(): Long

    fun countIndexedVerses(model: String, dimensions: Int): Long
}

/** OpenAI 같은 LLM 호출 구현체를 application layer와 분리하는 completion 포트입니다. */
interface AiCompletionPort {
    suspend fun answerWithCitations(
        question: String,
        citations: List<BibleCitation>,
        config: AiRuntimeConfigSnapshot,
        experiencePolicy: AiAssistantExperiencePolicy,
        conversationContext: AiConversationContext? = null,
        generationInstructions: List<String> = emptyList(),
        previousAnswer: String? = null,
        revisionInstructions: List<String> = emptyList(),
    ): GroundedAnswer
}

/** LLM이 생성한 답변 조각을 완료 전부터 전달하는 실시간 completion 포트입니다. */
interface AiStreamingCompletionPort {
    suspend fun answerWithCitations(
        question: String,
        citations: List<BibleCitation>,
        config: AiRuntimeConfigSnapshot,
        experiencePolicy: AiAssistantExperiencePolicy,
        conversationContext: AiConversationContext? = null,
        onDelta: suspend (String) -> Unit,
    ): GroundedAnswer
}

/** 프리미엄 상담 문맥의 소유권, 만료, 암호화 저장을 application layer와 분리합니다. */
interface AiConversationPort {
    fun load(userId: UUID, conversationId: UUID): AiConversationContext

    fun append(
        userId: UUID,
        conversationId: UUID?,
        turn: AiConversationTurn,
        maxContextTurns: Int,
    ): UUID
}

/** 생성 답변을 성경 근거와 비교해 구조화 점수로 검증하는 평가 모델 포트입니다. */
interface AiAnswerEvaluationPort {
    suspend fun evaluate(
        question: String,
        citations: List<BibleCitation>,
        answer: String,
        config: AiRuntimeConfigSnapshot,
    ): AiAnswerEvaluation
}

/** 동일하거나 재사용 가능한 AI 질문 답변을 저장해 비용을 줄이는 캐시 포트입니다. */
interface AiCachePort {
    fun get(userId: UUID, question: String, cacheScope: String): GroundedAnswer?

    fun put(
        userId: UUID,
        question: String,
        cacheScope: String,
        answer: GroundedAnswer,
    )

    /** 계정 삭제 후 해당 사용자가 접근한 AI 응답 캐시를 제거합니다. */
    fun evictUser(userId: UUID)
}

/** AI 요청을 호출 전에 예약하고 성공/실패를 확정해 quota 우회를 방지하는 사용량 포트입니다. */
interface AiUsagePort {
    fun countToday(userId: UUID): Long

    fun reserve(
        userId: UUID,
        question: String,
        dailyLimit: Long,
        config: AiRuntimeConfigSnapshot? = null,
        clientRequestId: UUID? = null,
        requestFingerprint: String = question,
    ): AiUsageReservation?

    fun findClientRequest(userId: UUID, clientRequestId: UUID): AiClientRequest?

    fun reserveRegeneration(
        userId: UUID,
        question: String,
        parentTurnId: UUID,
        config: AiRuntimeConfigSnapshot,
    ): UUID

    fun findCompletedTurn(userId: UUID, turnId: UUID): AiCompletedTurn?

    fun questionMatches(userId: UUID, turnId: UUID, question: String): Boolean

    fun complete(
        reservationId: UUID,
        answer: GroundedAnswer,
        question: String,
        modelVersion: Long,
    )

    fun fail(reservationId: UUID, errorCode: String, consumedAnswer: GroundedAnswer? = null)
}

/** 답변별 최종 피드백과 무료 재생성 상태를 영속 저장하는 포트입니다. */
interface AiFeedbackPort {
    fun upsert(
        userId: UUID,
        turnId: UUID,
        rating: AiFeedbackRating,
        reasons: Set<AiFeedbackReason>,
        comment: String?,
    ): AiAnswerFeedback

    fun beginRegeneration(userId: UUID, turnId: UUID): AiAnswerFeedback

    fun completeRegeneration(userId: UUID, turnId: UUID, regeneratedTurnId: UUID)

    fun failRegeneration(userId: UUID, turnId: UUID)

    fun summary(): AiFeedbackSummary

    fun priority(limit: Int): List<AiAnswerFeedback>
}

/** 현재 질문의 의미를 판단하며 대화 문맥은 짧은 후속 답변 해석에만 사용합니다. */
interface AiQuestionScopePort {
    suspend fun assess(
        currentQuestion: String,
        conversationContext: AiConversationContext?,
        config: AiRuntimeConfigSnapshot,
    ): com.example.mypasture.ai.domain.AiQuestionScopeAssessment
}
