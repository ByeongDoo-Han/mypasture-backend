package com.example.mypasture.ai.domain

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** RAG로 검색된 성경 구절과 출처 정보를 표현하는 값 객체입니다. */
data class BibleCitation(
    val verseId: Long,
    val version: String,
    val book: String,
    val chapter: Int,
    val verse: Int,
    val text: String,
    val bookCode: String = "",
)

/** 모바일이 AI 응답을 동일한 renderer로 표시할 수 있게 고정한 응답 계약 버전입니다. */
const val AI_ASSISTANT_RESPONSE_SCHEMA_VERSION = "assistant-response-v2"

/** 프롬프트·검색·답변 정책이 바뀔 때 올리는 AI 목자 구현 버전입니다. */
const val AI_PASTOR_VERSION = "ai-pastor-v1"

/** AI 답변 그룹은 원본 생성 시각부터 이 기간 동안만 조회할 수 있습니다. */
const val AI_ANSWER_HISTORY_RETENTION_DAYS = 365L

/** 무료 일회성 답변과 프리미엄 연속 대화를 구분합니다. */
enum class AiInteractionMode {
    ONE_SHOT,
    GUIDED_DIALOGUE,
}

/** AI 응답 이후 클라이언트가 기다려야 할 다음 사용자 동작입니다. */
enum class AiAssistantNextAction {
    COMPLETE,
    WAIT_USER_INPUT,
}

/** 플랜별 AI 목자 경험을 prompt와 application 정책에 동일하게 전달합니다. */
data class AiAssistantExperiencePolicy(
    val interactionMode: AiInteractionMode,
    val scriptureCount: Int,
    val includePrayer: Boolean,
    val allowFollowUp: Boolean,
    val maxContextTurns: Int,
)

/** 구조화 출력에서 답변과 분리된 추천 기도문입니다. */
data class AiRecommendedPrayer(
    val title: String,
    val content: String,
)

/** 프리미엄 대화를 이어가기 위한 한 개의 집중된 후속 질문입니다. */
data class AiFollowUpQuestion(
    val question: String,
    val options: List<String>,
    val allowSkip: Boolean = true,
)

/** 민감한 상담 문맥을 암호화해 보존할 때 사용하는 제한된 대화 turn입니다. */
data class AiConversationTurn(
    val userQuestion: String,
    val assistantAnswer: String,
    val followUpQuestion: String?,
)

/** 프리미엄 사용자가 소유한 최근 대화 문맥입니다. */
data class AiConversationContext(
    val conversationId: UUID,
    val turns: List<AiConversationTurn>,
) {
    fun retrievalQuestion(currentQuestion: String): String {
        val previousQuestion = turns.lastOrNull()?.userQuestion ?: return currentQuestion
        return "$previousQuestion\n추가로 알려준 내용: $currentQuestion"
    }

    fun promptContext(): String = turns.joinToString("\n") { turn ->
        buildString {
            append("사용자: ").append(turn.userQuestion)
            append("\nAI 목자: ").append(turn.assistantAnswer)
            turn.followUpQuestion?.let { append("\nAI 목자의 후속 질문: ").append(it) }
        }
    }
}

/** 만료되었거나 다른 사용자가 소유한 AI 대화에 접근할 때 발생합니다. */
class AiConversationUnavailableException : RuntimeException("AI 대화를 찾을 수 없거나 이미 만료되었습니다.")

/** RAG가 사용한 검색 방식으로 운영 중 vector index 누락과 lexical fallback을 구분합니다. */
enum class RagRetrievalStrategy {
    DIRECT_REFERENCE,
    PGVECTOR,
    LEXICAL_FALLBACK,
}

/** 사용자 질문의 목적을 구분해 검색 문맥과 답변 품질을 일관되게 유지합니다. */
enum class BibleQuestionIntent {
    PASTORAL_COUNSELING,
    BIBLE_EXPLANATION,
    PRAYER,
    VERSE_RECOMMENDATION,
    DEVOTIONAL,
    BIBLE_QA,
}

/** 사용자가 명시한 성경 권·장·절입니다. null 장/절은 권 전체를 의미합니다. */
data class BiblePassageReference(
    val bookCode: String,
    val bookName: String,
    val chapter: Int? = null,
    val verse: Int? = null,
)

/** 원문 질문과 RAG 검색에 최적화한 의미 질의를 분리한 application 입력 모델입니다. */
data class UnderstoodBibleQuestion(
    val originalQuestion: String,
    val searchQuery: String,
    val intent: BibleQuestionIntent,
    val lexicalKeywords: List<String>,
    val reference: BiblePassageReference? = null,
)

/** 모바일이 실제 AI 처리 순서를 사용자에게 안내할 때 사용하는 단계입니다. */
enum class AiAssistantStage {
    UNDERSTANDING_QUESTION,
    SEARCHING_SCRIPTURE,
    GENERATING_ANSWER,
    REVIEWING_ANSWER,
}

/** 검색된 성경 근거와 질문 embedding 사용량을 completion 단계로 전달합니다. */
data class BibleRetrievalResult(
    val citations: List<BibleCitation>,
    val embeddingTokens: Int = 0,
    val embeddingCostUsd: BigDecimal = BigDecimal.ZERO,
    val strategy: RagRetrievalStrategy = RagRetrievalStrategy.LEXICAL_FALLBACK,
)

/** embedding 제공자가 반환한 순서가 보존된 vector batch와 사용량입니다. */
data class TextEmbeddingBatch(
    val vectors: List<FloatArray>,
    val promptTokens: Int,
    val estimatedCostUsd: BigDecimal,
)

/** 성경 절 하나를 embedding index에 적재하기 위한 검증된 원본입니다. */
data class BibleEmbeddingSource(
    val verseId: Long,
    val content: String,
    val contentHash: String,
)

/** 관리자와 worker가 사용하는 성경 embedding index 진행 상태입니다. */
data class BibleEmbeddingIndexSummary(
    val totalVerses: Long,
    val indexedVerses: Long,
    val processedInBatch: Int,
    val remainingVerses: Long,
    val lockAcquired: Boolean,
    val promptTokens: Int = 0,
    val estimatedCostUsd: BigDecimal = BigDecimal.ZERO,
)

/** AI 답변, 근거 구절, 토큰 사용량, 비용 정보를 함께 담는 응답 모델입니다. */
data class GroundedAnswer(
    val conversationId: UUID?,
    val turnId: UUID = UUID.randomUUID(),
    val answer: String,
    val citations: List<BibleCitation>,
    val promptTokens: Int,
    val completionTokens: Int,
    val estimatedCostUsd: BigDecimal,
    val cacheHit: Boolean,
    val embeddingTokens: Int = 0,
    val embeddingCostUsd: BigDecimal = BigDecimal.ZERO,
    val retrievalStrategy: RagRetrievalStrategy = RagRetrievalStrategy.LEXICAL_FALLBACK,
    val questionIntent: BibleQuestionIntent = BibleQuestionIntent.BIBLE_QA,
    val modelConfigVersionId: UUID? = null,
    val generatorModel: String? = null,
    val evaluatorModel: String? = null,
    val evaluationPromptTokens: Int = 0,
    val evaluationCompletionTokens: Int = 0,
    val evaluationCostUsd: BigDecimal = BigDecimal.ZERO,
    val evaluationPassed: Boolean? = null,
    val evaluationGroundingScore: Int? = null,
    val evaluationCitationScore: Int? = null,
    val evaluationSafetyScore: Int? = null,
    val evaluationRelevanceScore: Int? = null,
    val evaluationPastoralToneScore: Int? = null,
    val evaluationIssues: List<String> = emptyList(),
    val revisionCount: Int = 0,
    val prayer: AiRecommendedPrayer? = null,
    val followUpQuestion: AiFollowUpQuestion? = null,
    val interactionMode: AiInteractionMode = AiInteractionMode.ONE_SHOT,
)

/** 클라이언트가 같은 논리 요청의 처리 상태와 최종 결과를 안전하게 다시 조회할 때 사용합니다. */
enum class AiClientRequestStatus {
    PROCESSING,
    COMPLETED,
    FAILED,
    EXPIRED,
}

/** 사용자별 clientRequestId에 연결된 AI 요청 영수증입니다. */
data class AiClientRequest(
    val clientRequestId: UUID,
    val turnId: UUID,
    val status: AiClientRequestStatus,
    val answer: GroundedAnswer? = null,
    val errorCode: String? = null,
    val updatedAt: Instant,
)

class AiIdempotencyConflictException : RuntimeException("같은 요청 ID를 다른 AI 질문에 사용할 수 없습니다.")
class AiRequestInProgressException : RuntimeException("기존 AI 요청을 처리하고 있습니다.")
class AiRequestPreviouslyFailedException : RuntimeException("이 AI 요청은 이전에 실패했습니다. 새 요청으로 다시 시도해 주세요.")
class AiRequestResultExpiredException : RuntimeException("저장된 AI 요청 결과가 만료되었습니다. 새 요청으로 다시 시도해 주세요.")
class AiRequestNotFoundException : RuntimeException("AI 요청을 찾을 수 없습니다.")

/** quota 예약 결과를 신규 실행과 기존 요청 재사용으로 구분합니다. */
sealed interface AiUsageReservation {
    data class Started(val turnId: UUID) : AiUsageReservation
    data class Existing(val request: AiClientRequest) : AiUsageReservation
}

/** AI 입력 제한과 무료 사용자 사용량 제한 정책을 표현하는 도메인 모델입니다. */
data class AiUsagePolicy(
    val maxInputChars: Int,
    val freeDailyLimit: Int,
)

/** 특정 사용자의 하루 AI 사용량 집계 결과를 표현하는 도메인 모델입니다. */
data class DailyAiUsage(
    val userId: UUID,
    val dateStart: Instant,
    val count: Long,
)
