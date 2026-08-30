package com.example.mypasture.ai.domain

import com.example.mypasture.user.domain.SubscriptionTier
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

enum class AiConversationStatus { ACTIVE, CLOSED }

/** 프리미엄 AI 대화의 소유권과 암호화된 최근 문맥만 보존하는 엔티티입니다. */
@Entity
@Table(
    name = "ai_conversations",
    indexes = [Index(name = "ix_ai_conversations_user_updated", columnList = "user_id,updated_at")],
)
class AiConversationEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "user_id", nullable = false)
    val userId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(name = "plan_snapshot", nullable = false, length = 20)
    val planSnapshot: SubscriptionTier,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: AiConversationStatus = AiConversationStatus.ACTIVE,
    @Column(name = "encrypted_context", nullable = false, columnDefinition = "text")
    var encryptedContext: String,
    @Column(name = "turn_count", nullable = false)
    var turnCount: Int = 0,
    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    fun append(encryptedContext: String, now: Instant, expiresAt: Instant) {
        this.encryptedContext = encryptedContext
        turnCount += 1
        updatedAt = now
        this.expiresAt = expiresAt
    }
}
