package com.example.mypasture.admin_audit.infrastructure

import com.example.mypasture.admin_audit.domain.AdminAuditLogEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

/** 관리자 조치의 멱등성 확인과 대상별 감사 이력 조회를 담당합니다. */
@Repository
interface AdminAuditLogRepository : JpaRepository<AdminAuditLogEntity, UUID> {
    fun findByOperationId(operationId: UUID): AdminAuditLogEntity?

    fun findTop20ByTargetTypeAndTargetIdOrderByCreatedAtDesc(
        targetType: String,
        targetId: UUID,
    ): List<AdminAuditLogEntity>
}
