package com.example.mypasture.ai.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

enum class AiRequestReceiptStatus {
    PENDING,
    COMPLETED,
    FAILED,
}

/** 네트워크 재요청이 같은 AI 작업을 다시 실행하지 않도록 사용자별 요청 영수증을 보관합니다. */
@Entity
@Table(
    name = "ai_request_receipts",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_ai_request_receipt_user_client",
            columnNames = ["user_id", "client_request_id"],
        ),
        UniqueConstraint(name = "uk_ai_request_receipt_usage", columnNames = ["usage_log_id"]),
    ],
    indexes = [Index(name = "ix_ai_request_receipt_status_updated", columnList = "status,updated_at")],
)
class AiRequestReceiptEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "user_id", nullable = false)
    val userId: UUID,
    @Column(name = "client_request_id", nullable = false)
    val clientRequestId: UUID,
    @Column(name = "usage_log_id", nullable = false)
    val usageLogId: UUID,
    @Column(name = "request_hash", nullable = false, length = 128)
    val requestHash: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: AiRequestReceiptStatus = AiRequestReceiptStatus.PENDING,
    @Column(name = "encrypted_response", columnDefinition = "text")
    var encryptedResponse: String? = null,
    @Column(name = "response_expires_at")
    var responseExpiresAt: Instant? = null,
    @Column(name = "error_code", length = 80)
    var errorCode: String? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt,
) {
    fun complete(encryptedResponse: String, responseExpiresAt: Instant, now: Instant) {
        status = AiRequestReceiptStatus.COMPLETED
        this.encryptedResponse = encryptedResponse
        this.responseExpiresAt = responseExpiresAt
        errorCode = null
        updatedAt = now
    }

    fun fail(code: String, now: Instant) {
        status = AiRequestReceiptStatus.FAILED
        encryptedResponse = null
        responseExpiresAt = null
        errorCode = code.take(80)
        updatedAt = now
    }
}
