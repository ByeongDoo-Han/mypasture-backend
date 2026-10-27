package com.example.mypasture.ai.infrastructure

import com.example.mypasture.ai.domain.AI_ASSISTANT_RESPONSE_SCHEMA_VERSION
import com.example.mypasture.ai.domain.AI_PASTOR_VERSION
import com.example.mypasture.ai.domain.AI_ANSWER_HISTORY_RETENTION_DAYS
import com.example.mypasture.ai.domain.AiAnswerHistoryEntity
import com.example.mypasture.ai.domain.GroundedAnswer
import com.example.mypasture.config.AiProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Page
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID
import jakarta.persistence.LockModeType
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 사용자 소유 기록만 조회하고 원본 질문을 안정적인 순서로 페이지 조회합니다. */
@Repository
interface AiAnswerHistoryRepository : JpaRepository<AiAnswerHistoryEntity, UUID> {
    fun findByTurnIdAndUserId(turnId: UUID, userId: UUID): AiAnswerHistoryEntity?
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from AiAnswerHistoryEntity h where h.turnId = :turnId and h.userId = :userId")
    fun lockByTurnIdAndUserId(turnId: UUID, userId: UUID): AiAnswerHistoryEntity?
    fun findAllByUserIdAndParentTurnIdOrderByCreatedAtAscTurnIdAsc(userId: UUID, parentTurnId: UUID): List<AiAnswerHistoryEntity>
    @Query("select h.parentTurnId, count(h) from AiAnswerHistoryEntity h where h.userId = :userId and h.parentTurnId in :rootIds group by h.parentTurnId")
    fun countRevisions(userId: UUID, rootIds: Collection<UUID>): List<Array<Any>>

    @Query("select h.parentTurnId, count(h) from AiAnswerHistoryEntity h where h.parentTurnId in :rootIds group by h.parentTurnId")
    fun adminRevisionCounts(rootIds: Collection<UUID>): List<Array<Any>>

    @Query(
        value = "select h from AiAnswerHistoryEntity h where h.parentTurnId is null and h.createdAt > :cutoff and " +
            "exists (select u.id from UserEntity u where u.id = h.userId and u.deletedAt is null) " +
            "order by h.createdAt desc, h.turnId desc",
        countQuery = "select count(h) from AiAnswerHistoryEntity h where h.parentTurnId is null and h.createdAt > :cutoff and " +
            "exists (select u.id from UserEntity u where u.id = h.userId and u.deletedAt is null)",
    )
    fun adminPage(cutoff: Instant, pageable: Pageable): Page<AiAnswerHistoryEntity>

    @Query("select h from AiAnswerHistoryEntity h where h.userId = :userId and h.parentTurnId is null and h.createdAt > :cutoff order by h.createdAt desc, h.turnId desc")
    fun firstPage(userId: UUID, cutoff: Instant, pageable: Pageable): List<AiAnswerHistoryEntity>

    @Query("select h from AiAnswerHistoryEntity h where h.userId = :userId and h.parentTurnId is null and h.createdAt > :cutoff and " +
        "(h.createdAt < :createdAt or (h.createdAt = :createdAt and h.turnId < :turnId)) " +
        "order by h.createdAt desc, h.turnId desc")
    fun pageAfter(userId: UUID, createdAt: Instant, turnId: UUID, cutoff: Instant, pageable: Pageable): List<AiAnswerHistoryEntity>

    fun findTop500ByParentTurnIdIsNullAndCreatedAtBeforeOrderByCreatedAtAsc(cutoff: Instant): List<AiAnswerHistoryEntity>
    fun deleteAllByUserIdAndParentTurnId(userId: UUID, parentTurnId: UUID): Long

    // Scalar 조회는 동일 영속성 문맥에 남은 삭제 전 entity 값을 재사용하지 않습니다.
    @Query("select h.contextSnapshotCiphertext, h.contextSnapshotExpiresAt, h.parentTurnId, h.createdAt from AiAnswerHistoryEntity h where h.turnId = :turnId and h.userId = :userId")
    fun contextState(userId: UUID, turnId: UUID): List<Array<Any?>>

    @Query("select h.createdAt from AiAnswerHistoryEntity h where h.turnId = :turnId and h.userId = :userId")
    fun ownedCreatedAt(userId: UUID, turnId: UUID): Instant?

    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @Query("update AiAnswerHistoryEntity h set h.contextSnapshotCiphertext = null, h.contextSnapshotExpiresAt = null where h.userId = :userId and (h.contextSnapshotCiphertext is not null or h.contextSnapshotExpiresAt is not null)")
    fun invalidateContextSnapshots(userId: UUID): Int

    fun findTop500ByContextSnapshotExpiresAtLessThanEqualOrderByContextSnapshotExpiresAtAsc(now: Instant): List<AiAnswerHistoryEntity>

    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @Query("update AiAnswerHistoryEntity h set h.contextSnapshotCiphertext = null, h.contextSnapshotExpiresAt = null where h.userId = :userId and h.turnId in :turnIds and h.contextSnapshotExpiresAt <= :now")
    fun clearExpiredContextSnapshots(userId: UUID, turnIds: Collection<UUID>, now: Instant): Int
}

/** 기록 원문에 영수증·대화 문맥과 구분된 AES-GCM 도메인 키를 사용합니다. */
@Component
class AiAnswerHistoryCipher(properties: AiProperties, private val objectMapper: ObjectMapper) {
    private val key = SecretKeySpec(
        MessageDigest.getInstance("SHA-256")
            .digest((properties.privacyHashSecret + ":answer-history:v1").toByteArray()),
        "AES",
    )
    private val random = SecureRandom()

    fun encrypt(value: Any): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val bytes = cipher.doFinal(objectMapper.writeValueAsBytes(value))
        return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.size + bytes.size).put(iv).put(bytes).array())
    }

    fun decryptQuestion(payload: String): String = objectMapper.readValue(decrypt(payload))

    fun decryptAnswer(payload: String): GroundedAnswer = objectMapper.readValue(decrypt(payload))

    private fun decrypt(payload: String): ByteArray {
        val decoded = Base64.getDecoder().decode(payload)
        require(decoded.size > 12) { "Encrypted AI answer history is invalid." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, decoded.copyOfRange(0, 12)))
        return cipher.doFinal(decoded.copyOfRange(12, decoded.size))
    }
}

/** 사용량 완료 트랜잭션 안에서만 새 답변을 쓰고 turn ID 중복을 거부합니다. */
@Component
class AiAnswerHistoryWriter(
    private val repository: AiAnswerHistoryRepository,
    private val cipher: AiAnswerHistoryCipher,
    private val snapshotCipher: AiContextSnapshotCipher,
) {
    fun save(
        usage: com.example.mypasture.ai.domain.AiUsageLogEntity,
        question: String,
        answer: GroundedAnswer,
        modelVersion: Long,
        contextSnapshot: com.example.mypasture.ai.domain.AiContextSnapshot? = null,
    ) {
        if (repository.existsById(usage.id)) return
        if (contextSnapshot != null && !contextSnapshot.expiresAt.isAfter(Instant.now())) {
            throw com.example.mypasture.ai.application.AiQuestionScopeUnclearException()
        }
        val parentId = usage.parentTurnId?.let { sourceId ->
            // 사용량은 직접 개선한 답변을 가리키고, 이력은 최종 원본으로 묶습니다.
            val source = repository.findByTurnIdAndUserId(sourceId, usage.userId) ?: throw com.example.mypasture.ai.application.AiQuestionScopeUnclearException()
            val rootId = source.parentTurnId ?: source.turnId
            val root = repository.lockByTurnIdAndUserId(rootId, usage.userId) ?: throw com.example.mypasture.ai.application.AiQuestionScopeUnclearException()
            if (!root.createdAt.isAfter(Instant.now().minus(AI_ANSWER_HISTORY_RETENTION_DAYS, ChronoUnit.DAYS))) throw com.example.mypasture.ai.application.AiQuestionScopeUnclearException()
            // 잠금을 기다리는 동안 삭제가 먼저 완료됐다면 이력을 되살리지 않습니다.
            if (repository.findByTurnIdAndUserId(sourceId, usage.userId) == null) throw com.example.mypasture.ai.application.AiQuestionScopeUnclearException()
            rootId
        }
        repository.save(
            AiAnswerHistoryEntity(
                turnId = usage.id,
                userId = usage.userId,
                parentTurnId = parentId,
                conversationId = answer.conversationId,
                questionCiphertext = cipher.encrypt(question),
                responseCiphertext = cipher.encrypt(answer),
                contextSnapshotCiphertext = contextSnapshot?.let { snapshotCipher.encrypt(usage.userId, usage.id, it) },
                contextSnapshotExpiresAt = contextSnapshot?.expiresAt,
                modelConfigVersionId = usage.modelConfigVersionId,
                modelVersionNumber = modelVersion.takeIf { usage.modelConfigVersionId != null },
                generatorModel = usage.model,
                assistantVersion = AI_PASTOR_VERSION,
                responseSchemaVersion = AI_ASSISTANT_RESPONSE_SCHEMA_VERSION,
                createdAt = Instant.now(),
            ),
        )
    }
}

/** 기록의 365일 보유기간이 지난 원본과 해당 개선본을 함께 삭제합니다. */
@Component
class AiAnswerHistoryCleanupService(
    private val repository: AiAnswerHistoryRepository,
    private val users: com.example.mypasture.auth.infrastructure.UserRepository,
    private val snapshots: AiContextSnapshotLifecycle,
) {
    @Transactional
    fun deleteExpired(): Int {
        val cutoff = Instant.now().minus(AI_ANSWER_HISTORY_RETENTION_DAYS, ChronoUnit.DAYS)
        val expired = repository.findTop500ByParentTurnIdIsNullAndCreatedAtBeforeOrderByCreatedAtAsc(cutoff)
        // PostgreSQL UUID와 같은 문자열 순서로 배치의 모든 U를 먼저 잠근 뒤 H를 잠급니다.
        val active = expired.map { it.userId }.distinct().sortedBy(UUID::toString)
            .filter { users.findActiveByIdForUpdate(it) != null }.toSet()
        var deleted = 0
        val invalidatedUsers = mutableSetOf<UUID>()
        expired.filter { it.userId in active }.forEach { candidate ->
            val root = repository.lockByTurnIdAndUserId(candidate.turnId, candidate.userId) ?: return@forEach
            if (!root.createdAt.isBefore(cutoff)) return@forEach
            if (invalidatedUsers.add(root.userId)) snapshots.invalidateUser(root.userId)
            repository.deleteAllByUserIdAndParentTurnId(root.userId, root.turnId)
            repository.delete(root)
            deleted++
        }
        return deleted
    }

    @Transactional
    fun clearExpiredSnapshots(): Int {
        val now = Instant.now()
        val expired = repository.findTop500ByContextSnapshotExpiresAtLessThanEqualOrderByContextSnapshotExpiresAtAsc(now)
        val active = expired.map { it.userId }.distinct().sortedBy(UUID::toString)
            .filter { users.findActiveByIdForUpdate(it) != null }.toSet()
        return expired.filter { it.userId in active }.groupBy { it.userId }.entries.sortedBy { it.key.toString() }
            .sumOf { (userId, rows) -> repository.clearExpiredContextSnapshots(userId, rows.map { it.turnId }, now) }
    }
}

/** API 복제본이 아닌 worker에서 만료된 암호화 기록을 정리합니다. */
@Component
@ConditionalOnProperty(prefix = "app.worker", name = ["enabled"], havingValue = "true")
class AiAnswerHistoryCleanupJob(private val cleanupService: AiAnswerHistoryCleanupService) {
    @Scheduled(cron = "0 30 3 * * *")
    fun deleteExpired() {
        while (cleanupService.clearExpiredSnapshots() == 500) { /* 최대 500개 문맥씩 파기합니다. */ }
        while (cleanupService.deleteExpired() == 500) { /* 한 배치씩 트랜잭션을 마칩니다. */ }
    }
}
