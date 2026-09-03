package com.example.mypasture.admin_audit.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** 관리자가 운영 데이터에 가한 변경을 사유와 함께 추적하는 감사 동작입니다. */
enum class AdminAuditAction {
    QUIET_TIME_FORCE_CLOSED,
    QUIET_TIME_QR_REVOKED,
    AI_RUNTIME_CONFIG_ACTIVATED,
    AI_RUNTIME_CONFIG_ROLLED_BACK,
    DAILY_WORD_GENERATION_QUEUED,
    DAILY_WORD_UPDATED,
    DAILY_WORD_PUBLISHED,
    DAILY_WORD_REJECTED,
    DAILY_WORD_MANUAL_DRAFT_CREATED,
    DAILY_WORD_INCIDENT_ACKNOWLEDGED,
    DAILY_WORD_INCIDENT_RESOLVED,
    DAILY_WORD_INCIDENT_EMAIL_REQUEUED,
    COUPON_CAMPAIGN_CREATED,
    COUPON_CAMPAIGN_DEACTIVATED,
    MODERATION_REPORT_REVIEWED,
}

/** 관리자 변경 요청의 주체, 대상, 사유와 멱등 키를 영구 보존하는 JPA entity입니다. */
@Entity
@Table(name = "admin_audit_logs")
class AdminAuditLogEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    val operationId: UUID,
    val adminUserId: UUID,
    @Enumerated(EnumType.STRING)
    val action: AdminAuditAction,
    val targetType: String,
    val targetId: UUID,
    @Column(length = 500)
    val reason: String,
    @Column(columnDefinition = "text")
    val details: String,
    @Column(length = 64)
    val requestFingerprint: String? = null,
    val createdAt: Instant = Instant.now(),
)
