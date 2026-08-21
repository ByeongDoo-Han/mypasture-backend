package com.example.mypasture.auth.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** 이메일 인증번호 해시와 일회성 회원가입 토큰의 수명 및 소비 상태를 관리합니다. */
@Entity
@Table(name = "email_signup_verifications")
class EmailSignupVerificationEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(nullable = false, unique = true, length = 320)
    val email: String,
    @Column(name = "code_hash", nullable = false, length = 64)
    var codeHash: String,
    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,
    @Column(name = "verification_token_hash", unique = true, length = 64)
    var verificationTokenHash: String? = null,
    @Column(name = "verification_token_expires_at")
    var verificationTokenExpiresAt: Instant? = null,
    @Column(name = "verified_at")
    var verifiedAt: Instant? = null,
    @Column(name = "consumed_at")
    var consumedAt: Instant? = null,
    @Column(name = "failed_attempts", nullable = false)
    var failedAttempts: Int = 0,
    @Column(name = "sent_at", nullable = false)
    var sentAt: Instant,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = sentAt,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = sentAt,
) {
    fun refresh(codeHash: String, expiresAt: Instant, now: Instant) {
        this.codeHash = codeHash
        this.expiresAt = expiresAt
        verificationTokenHash = null
        verificationTokenExpiresAt = null
        verifiedAt = null
        consumedAt = null
        failedAttempts = 0
        sentAt = now
        updatedAt = now
    }

    fun canAttemptAt(now: Instant, maxAttempts: Int): Boolean =
        consumedAt == null && verifiedAt == null && expiresAt.isAfter(now) && failedAttempts < maxAttempts

    fun recordFailure(now: Instant) {
        failedAttempts += 1
        updatedAt = now
    }

    fun markVerified(tokenHash: String, tokenExpiresAt: Instant, now: Instant) {
        verificationTokenHash = tokenHash
        verificationTokenExpiresAt = tokenExpiresAt
        verifiedAt = now
        updatedAt = now
    }

    fun canConsumeAt(now: Instant): Boolean =
        verifiedAt != null && consumedAt == null && verificationTokenExpiresAt?.isAfter(now) == true

    fun consume(now: Instant) {
        consumedAt = now
        updatedAt = now
    }
}
