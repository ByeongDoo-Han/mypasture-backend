package com.example.mypasture.ai.infrastructure

import com.example.mypasture.ai.application.AiContextSnapshotPort
import com.example.mypasture.ai.application.AiQuestionScopeUnclearException
import com.example.mypasture.ai.domain.*
import com.example.mypasture.config.AiProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 사용자·원 턴 AAD와 용도별 키로 상담 문맥 복제본의 전용 암호화를 수행합니다. */
@Component
class AiContextSnapshotCipher(properties: AiProperties, private val mapper: ObjectMapper) {
    private val key = SecretKeySpec(MessageDigest.getInstance("SHA-256")
        .digest((properties.privacyHashSecret + ":context-snapshot:v1").toByteArray()), "AES")
    private val random = SecureRandom()

    fun encrypt(userId: UUID, turnId: UUID, snapshot: AiContextSnapshot): String {
        validate(snapshot, snapshot.expiresAt)
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(aad(userId, turnId))
        val encrypted = cipher.doFinal(mapper.writeValueAsBytes(snapshot))
        return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.size + encrypted.size).put(iv).put(encrypted).array())
    }

    fun decrypt(userId: UUID, turnId: UUID, payload: String, expiresAt: Instant): AiContextSnapshot {
        val bytes = Base64.getDecoder().decode(payload)
        require(bytes.size in 29..128_000) { "Invalid AI context snapshot length" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(aad(userId, turnId))
        return mapper.readValue<AiContextSnapshot>(cipher.doFinal(bytes.copyOfRange(12, bytes.size)))
            .also { validate(it, expiresAt) }
    }

    private fun aad(userId: UUID, turnId: UUID) = "context-snapshot:v1:$userId:$turnId".toByteArray()

    private fun validate(snapshot: AiContextSnapshot, expiresAt: Instant) {
        require(snapshot.schemaVersion == 1 && snapshot.expiresAt == expiresAt &&
            snapshot.expiresAt == snapshot.capturedAt.plus(30, ChronoUnit.DAYS)) { "Invalid AI context snapshot metadata" }
        require(snapshot.context.turns.size in 1..4 && snapshot.context.turns.all {
            it.userQuestion.length <= 1_500 && it.assistantAnswer.length <= 1_500 && (it.followUpQuestion?.length ?: 0) <= 1_500
        }) { "Invalid AI context snapshot bounds" }
    }
}

/** 최신 대화 텍스트 없이 소유 이력의 당시 문맥을 복원하고 완료 트랜잭션에서 파기 경계를 검증합니다. */
@Component
class JpaAiContextSnapshotAdapter(
    private val history: AiAnswerHistoryRepository,
    private val conversations: AiConversationRepository,
    private val cipher: AiContextSnapshotCipher,
) : AiContextSnapshotPort {
    @Transactional(readOnly = true)
    override fun restore(userId: UUID, turnId: UUID): AiRestoredContextSnapshot? {
        val state = history.contextState(userId, turnId).firstOrNull() ?: return null
        val sourceCreatedAt = state[3] as? Instant ?: throw AiQuestionScopeUnclearException()
        val rootId = (state[2] as? UUID) ?: turnId
        val rootCreatedAt = if (rootId == turnId) sourceCreatedAt else history.ownedCreatedAt(userId, rootId)
        if (rootCreatedAt == null || !rootCreatedAt.isAfter(Instant.now().minus(AI_ANSWER_HISTORY_RETENTION_DAYS, ChronoUnit.DAYS))) {
            throw AiQuestionScopeUnclearException()
        }
        val payload = state[0] as? String
        val expiry = state[1] as? Instant
        if ((payload == null) != (expiry == null)) throw AiQuestionScopeUnclearException()
        val snapshot = if (payload != null && expiry != null && expiry.isAfter(Instant.now())) {
            val decoded = try { cipher.decrypt(userId, turnId, payload, expiry) }
                catch (_: Exception) { throw AiQuestionScopeUnclearException() }
            if (!conversations.existsOwned(decoded.context.conversationId, userId)) throw AiQuestionScopeUnclearException()
            decoded
        } else null
        return AiRestoredContextSnapshot(turnId, payload, expiry, snapshot, sourceCreatedAt, rootId, rootCreatedAt)
    }

    /** 호출자는 active user 행 잠금을 먼저 획득하며 삭제·만료 sweep도 같은 잠금 순서를 지킵니다. */
    fun validateForCompletion(userId: UUID, context: AiTurnCompletionContext) {
        // legacy·문맥 없는 첫 턴·만료된 문맥도 원 턴의 삭제 경계는 동일하게 적용합니다.
        context.restoredSource?.let { source ->
            val state = history.contextState(userId, source.sourceTurnId).firstOrNull() ?: throw AiQuestionScopeUnclearException()
            val rootId = (state[2] as? UUID) ?: source.sourceTurnId
            val rootCreatedAt = if (rootId == source.sourceTurnId) state[3] as? Instant else history.ownedCreatedAt(userId, rootId)
            if (state[3] != source.sourceCreatedAt || rootId != source.rootTurnId || rootCreatedAt != source.rootCreatedAt ||
                rootCreatedAt == null || !rootCreatedAt.isAfter(Instant.now().minus(AI_ANSWER_HISTORY_RETENTION_DAYS, ChronoUnit.DAYS)) ||
                source.snapshot != context.snapshot) {
                throw AiQuestionScopeUnclearException()
            }
            if (source.snapshot != null && (state[0] != source.sourceCiphertext || state[1] != source.sourceExpiresAt)) {
                throw AiQuestionScopeUnclearException()
            }
        }
        val snapshot = context.snapshot ?: return
        if (!snapshot.expiresAt.isAfter(Instant.now()) || !conversations.existsOwned(snapshot.context.conversationId, userId)) {
            throw AiQuestionScopeUnclearException()
        }
    }

}

/** 본인 기록 삭제 시 다른 답변 본문은 보존하고 복원용 문맥과 현재 대화만 함께 파기합니다. */
@Component
class AiContextSnapshotLifecycle(
    private val history: AiAnswerHistoryRepository,
    private val conversations: AiConversationRepository,
) {
    /** 호출자는 같은 사용자 행 잠금을 보유합니다. */
    fun invalidateUser(userId: UUID) {
        history.invalidateContextSnapshots(userId)
        conversations.deleteAllByUserId(userId)
    }
}
