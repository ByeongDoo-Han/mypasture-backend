package com.example.mypasture.billing.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/** 스토어에 전달할 비식별 계정 토큰과 사용자별 검증 작업 lease/fencing 번호입니다. */
@Entity
@Table(name = "billing_accounts")
class BillingAccountEntity(
    @Id val userId: UUID,
    @Column(nullable = false, unique = true) val accountToken: UUID = UUID.randomUUID(),
    @Column(nullable = false) var revision: Long = 0,
    var leaseToken: UUID? = null,
    var leaseExpiresAt: Instant? = null,
)

enum class BillingVerificationPhase { VERIFY, ACKNOWLEDGE, COMPLETE, REJECTED, DEAD_LETTER }

/** 교체된 Google purchaseToken이 이후 재조회로 이전 권한을 부활시키지 못하게 계열을 봉인합니다. */
@Entity
@Table(name = "billing_sources")
class BillingSourceEntity(
    @Id @Column(length = 64) val id: String,
    @Column(nullable = false) val userId: UUID,
    @Column(length = 64) var supersededBy: String? = null,
)

/** 재시작해도 유실되지 않는 검증/승인 작업입니다. 스토어 토큰 원문은 암호화해서만 저장합니다. */
@Entity
@Table(name = "billing_verification_jobs", uniqueConstraints = [UniqueConstraint(columnNames = ["user_id", "request_id"])])
class BillingVerificationJobEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(nullable = false) val userId: UUID,
    @Column(nullable = false) val requestId: UUID,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) val store: BillingStore,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) val environment: BillingEnvironment,
    @Column(nullable = false, length = 200) val productId: String,
    @Column(nullable = false, length = 64) val requestHash: String,
    @Column(nullable = false, columnDefinition = "text") val encryptedProof: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) var phase: BillingVerificationPhase = BillingVerificationPhase.VERIFY,
    @Column(nullable = false) var attempts: Int = 0,
    @Column(nullable = false) var recurring: Boolean = false,
    @Column(nullable = false) var refreshEnabled: Boolean = false,
    @Column(length = 80) var lastError: String? = null,
    @Column(nullable = false) var nextAttemptAt: Instant = Instant.now(),
    @Column(nullable = false) val createdAt: Instant = Instant.now(),
    @Column(nullable = false) var updatedAt: Instant = createdAt,
)
