package com.example.mypasture.auth.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** 계정당 하나의 최신 challenge와 재발급으로 초기화되지 않는 영속적 제한을 관리합니다. */
@Entity
@Table(name = "admin_email_login_states")
class AdminEmailLoginStateEntity(
    @Id val userId: UUID,
    @Column(unique = true, nullable = false) var challengeId: UUID = UUID.randomUUID(),
    @Column(length = 64) var codeHash: String? = null,
    var authVersion: Int = 0,
    var expiresAt: Instant? = null,
    var consumedAt: Instant? = null,
    var lastSentAt: Instant? = null,
    @Column(nullable = false) var failedAttempts: Int = 0,
    var failureWindowStartedAt: Instant? = null,
    @Column(nullable = false) var sendCount: Int = 0,
    var sendWindowStartedAt: Instant? = null,
) {
    fun refreshWindows(now: Instant) {
        if (failureWindowStartedAt?.plusSeconds(600)?.isAfter(now) != true) {
            failedAttempts = 0
            failureWindowStartedAt = null
        }
        if (sendWindowStartedAt?.plusSeconds(3600)?.isAfter(now) != true) {
            sendCount = 0
            sendWindowStartedAt = null
        }
    }

    fun recordFailure(now: Instant) {
        if (failureWindowStartedAt == null) failureWindowStartedAt = now
        failedAttempts = (failedAttempts + 1).coerceAtMost(5)
    }

    fun canSendAt(now: Instant): Boolean = sendCount < 5 && failedAttempts < 5 &&
        lastSentAt?.plusSeconds(60)?.isAfter(now) != true

    fun beginChallenge(id: UUID, hash: String, version: Int, now: Instant) {
        challengeId = id
        codeHash = hash
        authVersion = version
        expiresAt = now.plusSeconds(300)
        consumedAt = null
        lastSentAt = now
        if (sendWindowStartedAt == null) sendWindowStartedAt = now
        sendCount += 1
    }

    fun consume(now: Instant) {
        consumedAt = now
        codeHash = null
    }
}
