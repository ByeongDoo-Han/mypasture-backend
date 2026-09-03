package com.example.mypasture.user.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** 사용자 식별자를 남기지 않고 회원탈퇴 파기 작업의 완료 사실과 처리량만 기록합니다. */
@Entity
@Table(name = "account_erasure_audits")
class AccountErasureAuditEntity(
    @Id
    val id: UUID,
    @Column(name = "requested_at", nullable = false)
    val requestedAt: Instant,
    @Column(name = "completed_at", nullable = false)
    val completedAt: Instant,
    @Column(name = "policy_version", nullable = false, length = 40)
    val policyVersion: String,
    @Column(name = "deleted_record_count", nullable = false)
    val deletedRecordCount: Long,
    @Column(name = "anonymized_record_count", nullable = false)
    val anonymizedRecordCount: Long,
)
