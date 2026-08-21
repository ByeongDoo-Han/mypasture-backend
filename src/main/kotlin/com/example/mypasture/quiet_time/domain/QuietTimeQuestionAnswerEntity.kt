package com.example.mypasture.quiet_time.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

/** QT 본문의 질문 종류를 안정적인 API 및 DB 값으로 표현합니다. */
enum class QuietTimeQuestionCategory { OBSERVATION, MEDITATION, SHARING }

/** 참여자가 모임의 질문 하나에 게시한 최신 답변을 저장합니다. */
@Entity
@Table(
    name = "quiet_time_question_answers",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_quiet_time_answer_author_question",
            columnNames = ["session_id", "user_id", "question_category", "question_index"],
        ),
    ],
)
class QuietTimeQuestionAnswerEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    val session: QuietTimeSessionEntity,
    @Column(name = "user_id", nullable = false)
    val userId: UUID,
    @Column(name = "author_display_name", nullable = false, length = 80)
    var authorDisplayName: String,
    @Enumerated(EnumType.STRING)
    @Column(name = "question_category", nullable = false, length = 20)
    val questionCategory: QuietTimeQuestionCategory,
    @Column(name = "question_index", nullable = false)
    val questionIndex: Int,
    @Column(name = "question_text", nullable = false, length = 500)
    val questionText: String,
    @Column(nullable = false, columnDefinition = "text")
    var content: String,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt,
) {
    fun update(authorDisplayName: String, content: String, now: Instant) {
        this.authorDisplayName = authorDisplayName
        this.content = content
        updatedAt = now
    }
}
