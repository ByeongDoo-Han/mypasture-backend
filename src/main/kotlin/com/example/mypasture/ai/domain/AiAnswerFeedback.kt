package com.example.mypasture.ai.domain

import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** 사용자가 AI 목자 답변을 유용하다고 느꼈는지 구분합니다. */
enum class AiFeedbackRating { HELPFUL, NOT_HELPFUL }

/** 운영자가 품질 문제를 집계하고 서버가 안전한 개선 지침으로 변환하는 사유입니다. */
enum class AiFeedbackReason {
    MISUNDERSTOOD_QUESTION,
    IRRELEVANT_SCRIPTURE,
    TOO_GENERIC,
    HARMFUL_OR_OVERCONFIDENT,
    WRONG_OR_NONEXISTENT_SCRIPTURE,
    OTHER,
}

/** 무료 개선 답변의 중복 생성과 장애 후 재시도를 제어하는 상태입니다. */
enum class AiFeedbackRegenerationStatus { NONE, PENDING, COMPLETED, FAILED }

/** 다른 사용자 답변이거나 완료되지 않은 답변에 피드백을 남기려 할 때 발생합니다. */
class AiFeedbackTargetNotFoundException : RuntimeException("피드백 대상 AI 답변을 찾을 수 없습니다.")

/** 같은 답변의 개선 생성이 이미 진행 중일 때 발생합니다. */
class AiFeedbackRegenerationConflictException : RuntimeException("개선 답변을 이미 생성하고 있습니다.")

/** 도움됨 평가이거나 이미 개선 완료된 답변을 다시 생성하려 할 때 발생합니다. */
class AiFeedbackRegenerationUnavailableException : RuntimeException("이 답변은 다시 생성할 수 없습니다.")

/** 평가 사유나 선택 의견이 정책 범위를 벗어났을 때 발생합니다. */
class AiFeedbackInvalidException(message: String) : RuntimeException(message)

/** 사용자별 답변 평가와 암호화된 선택 의견, 개선 답변 추적 정보를 보존합니다. */
@Entity
@Table(name = "ai_answer_feedbacks")
class AiAnswerFeedbackEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "user_id", nullable = false)
    val userId: UUID,
    @Column(name = "turn_id", nullable = false)
    val turnId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var rating: AiFeedbackRating,
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "ai_answer_feedback_reasons", joinColumns = [JoinColumn(name = "feedback_id")])
    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 50)
    val reasons: MutableSet<AiFeedbackReason> = linkedSetOf(),
    @Column(name = "comment_ciphertext", columnDefinition = "text")
    var encryptedComment: String? = null,
    @Enumerated(EnumType.STRING)
    @Column(name = "regeneration_status", nullable = false, length = 20)
    var regenerationStatus: AiFeedbackRegenerationStatus = AiFeedbackRegenerationStatus.NONE,
    @Column(name = "regenerated_turn_id")
    var regeneratedTurnId: UUID? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    fun update(
        rating: AiFeedbackRating,
        reasons: Set<AiFeedbackReason>,
        encryptedComment: String?,
        now: Instant,
    ) {
        this.rating = rating
        this.reasons.clear()
        this.reasons.addAll(reasons)
        this.encryptedComment = encryptedComment
        updatedAt = now
        if (rating == AiFeedbackRating.HELPFUL && regenerationStatus != AiFeedbackRegenerationStatus.COMPLETED) {
            regenerationStatus = AiFeedbackRegenerationStatus.NONE
        }
    }

    fun beginRegeneration(now: Instant) {
        regenerationStatus = AiFeedbackRegenerationStatus.PENDING
        updatedAt = now
    }

    fun completeRegeneration(turnId: UUID, now: Instant) {
        regenerationStatus = AiFeedbackRegenerationStatus.COMPLETED
        regeneratedTurnId = turnId
        updatedAt = now
    }

    fun failRegeneration(now: Instant) {
        regenerationStatus = AiFeedbackRegenerationStatus.FAILED
        updatedAt = now
    }
}

/** 피드백 대상 답변의 소유권과 이전 RAG 근거를 application layer에 제공합니다. */
data class AiCompletedTurn(
    val turnId: UUID,
    val citationVerseIds: Set<Long>,
)

/** 모바일에 반환하는 최종 평가 상태입니다. 암호화된 자유 의견은 다시 노출하지 않습니다. */
data class AiAnswerFeedback(
    val turnId: UUID,
    val rating: AiFeedbackRating,
    val reasons: Set<AiFeedbackReason>,
    val regenerationStatus: AiFeedbackRegenerationStatus,
    val regeneratedTurnId: UUID?,
    val updatedAt: Instant,
)

/** 운영 화면에서 원문 상담 내용 없이 품질 추세를 확인하는 집계입니다. */
data class AiFeedbackSummary(
    val total: Long,
    val helpful: Long,
    val notHelpful: Long,
    val regenerated: Long,
    val byReason: Map<AiFeedbackReason, Long>,
)
