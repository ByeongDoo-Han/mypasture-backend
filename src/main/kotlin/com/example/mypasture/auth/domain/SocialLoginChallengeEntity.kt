package com.example.mypasture.auth.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** Google/Apple ID Token을 한 번의 로그인 시도에 묶는 단기 nonce challenge입니다. */
@Entity
@Table(name = "social_login_challenges")
class SocialLoginChallengeEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "nonce_hash", nullable = false, length = 64)
    val nonceHash: String,
    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,
    @Column(name = "used_at")
    var usedAt: Instant? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
) {
    fun isUsableAt(now: Instant): Boolean = usedAt == null && expiresAt.isAfter(now)

    fun consume(now: Instant) {
        usedAt = now
    }
}
