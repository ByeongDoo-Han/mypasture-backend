package com.example.mypasture.ai.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** 사용자의 완성된 AI 답변을 암호화해 원본과 개선본별로 보존합니다. */
@Entity
@Table(name = "ai_answer_history")
class AiAnswerHistoryEntity(
    @Id
    @Column(name = "turn_id")
    val turnId: UUID,
    @Column(name = "user_id", nullable = false)
    val userId: UUID,
    @Column(name = "parent_turn_id")
    val parentTurnId: UUID?,
    @Column(name = "conversation_id")
    val conversationId: UUID?,
    @Column(name = "question_ciphertext", nullable = false, columnDefinition = "text")
    val questionCiphertext: String,
    @Column(name = "response_ciphertext", nullable = false, columnDefinition = "text")
    val responseCiphertext: String,
    @Column(name = "model_config_version_id")
    val modelConfigVersionId: UUID?,
    @Column(name = "model_version_number")
    val modelVersionNumber: Long?,
    @Column(name = "generator_model", length = 80)
    val generatorModel: String?,
    @Column(name = "assistant_version", nullable = false, length = 80)
    val assistantVersion: String,
    @Column(name = "response_schema_version", nullable = false, length = 80)
    val responseSchemaVersion: String,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "context_snapshot_ciphertext", columnDefinition = "text")
    var contextSnapshotCiphertext: String? = null,
    @Column(name = "context_snapshot_expires_at")
    var contextSnapshotExpiresAt: Instant? = null,
)
