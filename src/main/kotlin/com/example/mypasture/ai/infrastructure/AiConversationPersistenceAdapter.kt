package com.example.mypasture.ai.infrastructure

import com.example.mypasture.ai.application.AiConversationPort
import com.example.mypasture.ai.domain.AiConversationContext
import com.example.mypasture.ai.domain.AiConversationEntity
import com.example.mypasture.ai.domain.AiConversationStatus
import com.example.mypasture.ai.domain.AiConversationTurn
import com.example.mypasture.ai.domain.AiConversationUnavailableException
import com.example.mypasture.config.AiProperties
import com.example.mypasture.user.domain.SubscriptionTier
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.transaction.Transactional
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AI 대화 소유권과 만료 시각을 함께 조회하는 repository입니다. */
@Repository
interface AiConversationRepository : JpaRepository<AiConversationEntity, UUID> {
    fun findByIdAndUserIdAndStatus(id: UUID, userId: UUID, status: AiConversationStatus): AiConversationEntity?
    fun deleteAllByExpiresAtBefore(now: Instant): Long
    fun deleteAllByUserId(userId: UUID): Long

    @org.springframework.data.jpa.repository.Query("select count(c) > 0 from AiConversationEntity c where c.id = :id and c.userId = :userId")
    fun existsOwned(id: UUID, userId: UUID): Boolean
}

/** 상담 문맥을 AES-GCM으로 암호화해 DB 유출 시 평문 노출을 줄입니다. */
@Component
class AiConversationCipher(properties: AiProperties) {
    private val key = SecretKeySpec(
        MessageDigest.getInstance("SHA-256").digest((properties.privacyHashSecret + ":conversation:v1").toByteArray()),
        "AES",
    )
    private val random = SecureRandom()

    fun encrypt(plainText: String): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.size + encrypted.size).put(iv).put(encrypted).array())
    }

    fun decrypt(payload: String): String {
        val decoded = Base64.getDecoder().decode(payload)
        require(decoded.size > 12) { "Encrypted AI conversation is invalid." }
        val iv = decoded.copyOfRange(0, 12)
        val encrypted = decoded.copyOfRange(12, decoded.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }
}

/** 최근 네 turn만 암호화해 저장하고 사용자별 대화 소유권과 30일 만료를 강제합니다. */
@Component
class JpaAiConversationAdapter(
    private val repository: AiConversationRepository,
    private val cipher: AiConversationCipher,
    private val objectMapper: ObjectMapper,
) : AiConversationPort {
    @Transactional
    override fun load(userId: UUID, conversationId: UUID): AiConversationContext {
        val entity = activeConversation(userId, conversationId)
        return AiConversationContext(entity.id, decode(entity.encryptedContext))
    }

    @Transactional
    override fun append(
        userId: UUID,
        conversationId: UUID?,
        turn: AiConversationTurn,
        maxContextTurns: Int,
    ): UUID {
        require(maxContextTurns > 0) { "Premium conversation context must retain at least one turn." }
        val now = Instant.now()
        val expiresAt = now.plus(CONVERSATION_TTL)
        if (conversationId == null) {
            val turns = listOf(turn).bounded(maxContextTurns)
            return repository.save(
                AiConversationEntity(
                    userId = userId,
                    planSnapshot = SubscriptionTier.PREMIUM,
                    encryptedContext = encode(turns),
                    turnCount = 1,
                    expiresAt = expiresAt,
                    createdAt = now,
                    updatedAt = now,
                ),
            ).id
        }

        val entity = activeConversation(userId, conversationId)
        val turns = (decode(entity.encryptedContext) + turn).bounded(maxContextTurns)
        entity.append(encode(turns), now, expiresAt)
        return repository.save(entity).id
    }

    private fun activeConversation(userId: UUID, conversationId: UUID): AiConversationEntity {
        val entity = repository.findByIdAndUserIdAndStatus(conversationId, userId, AiConversationStatus.ACTIVE)
            ?: throw AiConversationUnavailableException()
        if (!entity.expiresAt.isAfter(Instant.now())) {
            repository.delete(entity)
            throw AiConversationUnavailableException()
        }
        return entity
    }

    private fun encode(turns: List<AiConversationTurn>): String = cipher.encrypt(objectMapper.writeValueAsString(turns))

    private fun decode(payload: String): List<AiConversationTurn> = runCatching {
        objectMapper.readValue<List<AiConversationTurn>>(cipher.decrypt(payload))
    }.getOrElse { throw AiConversationUnavailableException() }

    private fun List<AiConversationTurn>.bounded(maxContextTurns: Int): List<AiConversationTurn> =
        takeLast(maxContextTurns).map { turn ->
            turn.copy(
                userQuestion = turn.userQuestion.take(MAX_CONTEXT_FIELD_CHARS),
                assistantAnswer = turn.assistantAnswer.take(MAX_CONTEXT_FIELD_CHARS),
                followUpQuestion = turn.followUpQuestion?.take(MAX_CONTEXT_FIELD_CHARS),
            )
        }

    companion object {
        private val CONVERSATION_TTL = Duration.ofDays(30)
        private const val MAX_CONTEXT_FIELD_CHARS = 1_500
    }
}

/** 만료된 암호화 대화 문맥을 주기적으로 제거해 개인정보 보관 기간을 제한합니다. */
@Component
class AiConversationCleanupJob(private val repository: AiConversationRepository) {
    @Scheduled(cron = "0 20 3 * * *")
    @Transactional
    fun deleteExpiredConversations() {
        repository.deleteAllByExpiresAtBefore(Instant.now())
    }
}
