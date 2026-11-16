package com.example.mypasture.ai.application

import com.example.mypasture.admin_audit.domain.AdminAuditAction
import com.example.mypasture.admin_audit.domain.AdminAuditLogEntity
import com.example.mypasture.admin_audit.infrastructure.AdminAuditLogRepository
import com.example.mypasture.ai.domain.AI_ANSWER_HISTORY_RETENTION_DAYS
import com.example.mypasture.ai.infrastructure.AiAnswerHistoryCipher
import com.example.mypasture.ai.infrastructure.AiAnswerHistoryRepository
import com.example.mypasture.auth.infrastructure.UserRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** 관리자 목록에 필요한 작성자·질문 정보만 담습니다. */
data class AiAdminHistoryItem(
    val turnId: UUID,
    val userId: UUID,
    val displayName: String,
    val email: String?,
    val questionPreview: String,
    val createdAt: Instant,
    val revisionCount: Long,
)

/** 안정적인 시간·ID 순서의 관리자 AI 기록 페이지입니다. */
data class AiAdminHistoryPage(
    val items: List<AiAdminHistoryItem>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)

/** 관리자 상세 열람 목적을 고정된 범위로 제한합니다. */
enum class AiAdminHistoryReviewReason { QUALITY_REVIEW, SAFETY_REVIEW, USER_SUPPORT }

/** 질문 원문과 답변 버전을 작성자 정보와 함께 표시합니다. */
data class AiAdminHistoryDetail(
    val userId: UUID,
    val displayName: String,
    val email: String?,
    val createdAt: Instant,
    val history: AiHistoryDetail,
)

/** 관리자 조회의 보유기간·활성 계정·열람 감사를 한 트랜잭션에서 적용합니다. */
@Service
class AiAdminHistoryService(
    private val records: AiAnswerHistoryRepository,
    private val users: UserRepository,
    private val cipher: AiAnswerHistoryCipher,
    private val history: AiAnswerHistoryService,
    private val audits: AdminAuditLogRepository,
) {
    @Transactional
    fun list(adminUserId: UUID, page: Int, size: Int): AiAdminHistoryPage {
        require(page in 0..100_000 && size in 1..50) { "AI 기록 조회 범위가 올바르지 않습니다." }
        val cutoff = Instant.now().minus(AI_ANSWER_HISTORY_RETENTION_DAYS, ChronoUnit.DAYS)
        val result = records.adminPage(cutoff, PageRequest.of(page, size))
        val owners = users.findAllById(result.content.map { it.userId }.distinct()).associateBy { it.id }
        val revisionCounts = if (result.isEmpty) emptyMap() else records.adminRevisionCounts(result.content.map { it.turnId })
            .associate { (it[0] as UUID) to (it[1] as Number).toLong() }
        val roots = result.content.mapNotNull { record ->
            val owner = owners[record.userId]?.takeIf { it.deletedAt == null } ?: return@mapNotNull null
            AiAdminHistoryItem(
                turnId = record.turnId,
                userId = owner.id,
                displayName = owner.displayName,
                email = owner.email,
                questionPreview = cipher.decryptQuestion(record.questionCiphertext).take(80),
                createdAt = record.createdAt,
                revisionCount = revisionCounts[record.turnId] ?: 0,
            )
        }
        audits.save(AdminAuditLogEntity(
            operationId = UUID.randomUUID(), adminUserId = adminUserId,
            action = AdminAuditAction.AI_HISTORY_LIST_VIEWED,
            targetType = "AI_HISTORY_LIST", targetId = adminUserId,
            reason = "AI 목자 기록 목록 조회", details = "page=$page,size=$size",
        ))
        return AiAdminHistoryPage(roots, result.number, result.size, result.totalElements, result.totalPages)
    }

    @Transactional
    fun detail(adminUserId: UUID, turnId: UUID, reason: AiAdminHistoryReviewReason): AiAdminHistoryDetail {
        val selected = records.findById(turnId).orElseThrow { AiHistoryNotFoundException() }
        val rootId = selected.parentTurnId ?: selected.turnId
        val root = records.findById(rootId).orElseThrow { AiHistoryNotFoundException() }
        val owner = users.findActiveByIdForUpdate(root.userId) ?: throw AiHistoryNotFoundException()
        val detail = history.detail(owner.id, rootId)
        audits.save(AdminAuditLogEntity(
            operationId = UUID.randomUUID(), adminUserId = adminUserId,
            action = AdminAuditAction.AI_HISTORY_DETAIL_VIEWED,
            targetType = "AI_HISTORY", targetId = rootId,
            reason = "AI 목자 기록 상세 조회", details = "reason=${reason.name}",
        ))
        return AiAdminHistoryDetail(owner.id, owner.displayName, owner.email, root.createdAt, detail)
    }
}
