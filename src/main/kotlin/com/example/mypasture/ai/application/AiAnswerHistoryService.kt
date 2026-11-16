package com.example.mypasture.ai.application

import com.example.mypasture.ai.domain.AiAnswerHistoryEntity
import com.example.mypasture.ai.domain.AI_ANSWER_HISTORY_RETENTION_DAYS
import com.example.mypasture.ai.domain.AiFollowUpQuestion
import com.example.mypasture.ai.domain.AiRecommendedPrayer
import com.example.mypasture.ai.domain.BibleCitation
import com.example.mypasture.ai.infrastructure.AiAnswerHistoryCipher
import com.example.mypasture.ai.infrastructure.AiAnswerHistoryRepository
import com.example.mypasture.config.AiProperties
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** 기록 목록의 한 질문과 개선본 수를 보여줍니다. */
data class AiHistoryItem(
    val turnId: UUID,
    val questionPreview: String,
    val createdAt: Instant,
    val hasPrayer: Boolean,
    val modelVersion: Long?,
    val assistantVersion: String,
    val revisionCount: Long,
)

/** 소유자 전용 질문 목록의 안정적인 커서 응답입니다. */
data class AiHistoryPage(val items: List<AiHistoryItem>, val nextCursor: String?)

/** 생성 당시 답변, 말씀, 기도문과 모델 버전을 보존한 보기용 값입니다. */
data class AiHistoryVersion(
    val turnId: UUID,
    val kind: String,
    val createdAt: Instant,
    val answer: String,
    val citations: List<BibleCitation>,
    val prayer: AiRecommendedPrayer?,
    val followUpQuestion: AiFollowUpQuestion?,
    val modelVersion: Long?,
    val generatorModel: String?,
    val assistantVersion: String,
    val responseSchemaVersion: String,
)

/** 원본 질문과 시간 순으로 연결된 답변 버전입니다. */
data class AiHistoryDetail(val rootTurnId: UUID, val question: String, val versions: List<AiHistoryVersion>)

/** 잘못되거나 다른 사용자의 목록 커서입니다. */
class AiHistoryInvalidCursorException : RuntimeException("AI 기록 커서가 올바르지 않습니다.")

/** 다른 사람 또는 없는 답변은 같은 404로 처리합니다. */
class AiHistoryNotFoundException : RuntimeException("AI 기록을 찾을 수 없습니다.")

/** 사용자의 완료 답변 기록에 대한 목록·상세·삭제 정책을 적용합니다. */
@Service
class AiAnswerHistoryService(
    private val repository: AiAnswerHistoryRepository,
    private val cipher: AiAnswerHistoryCipher,
    properties: AiProperties,
    private val users: com.example.mypasture.auth.infrastructure.UserRepository,
    private val snapshots: com.example.mypasture.ai.infrastructure.AiContextSnapshotLifecycle,
) {
    private val cursorKey = SecretKeySpec(
        MessageDigest.getInstance("SHA-256")
            .digest((properties.privacyHashSecret + ":answer-history-cursor:v1").toByteArray(StandardCharsets.UTF_8)),
        "HmacSHA256",
    )

    @Transactional(readOnly = true)
    fun list(userId: UUID, size: Int, cursor: String?): AiHistoryPage {
        require(size in 1..50) { "AI 기록 조회 크기는 1~50이어야 합니다." }
        val boundary = cursor?.let { decodeCursor(userId, it) }
        val cutoff = Instant.now().minus(AI_ANSWER_HISTORY_RETENTION_DAYS, ChronoUnit.DAYS)
        val records = if (boundary == null) {
            repository.firstPage(userId, cutoff, PageRequest.of(0, size + 1))
        } else {
            repository.pageAfter(userId, boundary.first, boundary.second, cutoff, PageRequest.of(0, size + 1))
        }
        val visible = records.take(size)
        val revisionCounts = if (visible.isEmpty()) emptyMap() else repository.countRevisions(userId, visible.map { it.turnId })
            .associate { (it[0] as UUID) to (it[1] as Number).toLong() }
        return AiHistoryPage(
            items = visible.map { record ->
                val answer = cipher.decryptAnswer(record.responseCiphertext)
                AiHistoryItem(
                    turnId = record.turnId,
                    questionPreview = cipher.decryptQuestion(record.questionCiphertext).take(80),
                    createdAt = record.createdAt,
                    hasPrayer = answer.prayer != null,
                    modelVersion = record.modelVersionNumber,
                    assistantVersion = record.assistantVersion,
                    revisionCount = revisionCounts[record.turnId] ?: 0,
                )
            },
            nextCursor = if (records.size > size) visible.lastOrNull()?.let { encodeCursor(userId, it) } else null,
        )
    }

    @Transactional(readOnly = true)
    fun detail(userId: UUID, turnId: UUID): AiHistoryDetail {
        val selected = repository.findByTurnIdAndUserId(turnId, userId) ?: throw AiHistoryNotFoundException()
        val rootId = selected.parentTurnId ?: selected.turnId
        val root = repository.findByTurnIdAndUserId(rootId, userId) ?: throw AiHistoryNotFoundException()
        if (!root.createdAt.isAfter(Instant.now().minus(AI_ANSWER_HISTORY_RETENTION_DAYS, ChronoUnit.DAYS))) {
            throw AiHistoryNotFoundException()
        }
        val versions = (listOf(root) + repository.findAllByUserIdAndParentTurnIdOrderByCreatedAtAscTurnIdAsc(userId, rootId))
            .sortedWith(compareBy(AiAnswerHistoryEntity::createdAt, AiAnswerHistoryEntity::turnId))
            .map { record ->
                val response = cipher.decryptAnswer(record.responseCiphertext)
                AiHistoryVersion(
                    turnId = record.turnId,
                    kind = if (record.parentTurnId == null) "ORIGINAL" else "REGENERATED",
                    createdAt = record.createdAt,
                    answer = response.answer,
                    citations = response.citations,
                    prayer = response.prayer,
                    followUpQuestion = response.followUpQuestion,
                    modelVersion = record.modelVersionNumber,
                    generatorModel = record.generatorModel,
                    assistantVersion = record.assistantVersion,
                    responseSchemaVersion = record.responseSchemaVersion,
                )
            }
        return AiHistoryDetail(rootId, cipher.decryptQuestion(root.questionCiphertext), versions)
    }

    @Transactional
    fun delete(userId: UUID, turnId: UUID) {
        val selected = repository.findByTurnIdAndUserId(turnId, userId) ?: return
        val rootId = selected.parentTurnId ?: selected.turnId
        if (users.findActiveByIdForUpdate(userId) == null) return
        val root = repository.lockByTurnIdAndUserId(rootId, userId) ?: return
        snapshots.invalidateUser(userId)
        repository.deleteAllByUserIdAndParentTurnId(userId, root.turnId)
        repository.delete(root)
    }

    private fun encodeCursor(userId: UUID, record: AiAnswerHistoryEntity): String {
        val payload = "$userId|${record.createdAt.epochSecond}|${record.createdAt.nano}|${record.turnId}"
        val signature = sign(payload)
        return Base64.getUrlEncoder().withoutPadding().encodeToString("$payload|$signature".toByteArray(StandardCharsets.UTF_8))
    }

    private fun decodeCursor(userId: UUID, cursor: String): Pair<Instant, UUID> = try {
        require(cursor.length <= 256)
        val decoded = String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
        val parts = decoded.split('|')
        require(parts.size == 5 && parts[0] == userId.toString())
        val payload = parts.take(4).joinToString("|")
        require(MessageDigest.isEqual(sign(payload).toByteArray(StandardCharsets.UTF_8), parts[4].toByteArray(StandardCharsets.UTF_8)))
        Instant.ofEpochSecond(parts[1].toLong(), parts[2].toLong()) to UUID.fromString(parts[3])
    } catch (_: RuntimeException) {
        throw AiHistoryInvalidCursorException()
    }

    private fun sign(payload: String): String = Mac.getInstance("HmacSHA256")
        .apply { init(cursorKey) }
        .doFinal(payload.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
