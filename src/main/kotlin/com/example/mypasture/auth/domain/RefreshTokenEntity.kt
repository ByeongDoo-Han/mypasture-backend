package com.example.mypasture.auth.domain

import com.example.mypasture.user.domain.UserEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** 원문을 저장하지 않고 해시만 보관하며 재사용 탐지가 가능한 refresh token입니다. */
@Entity
@Table(name = "auth_refresh_tokens")
class RefreshTokenEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    val user: UserEntity,
    @Column(name = "family_id", nullable = false)
    val familyId: UUID = UUID.randomUUID(),
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    val tokenHash: String,
    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,
    @Column(name = "used_at")
    var usedAt: Instant? = null,
    @Column(name = "revoked_at")
    var revokedAt: Instant? = null,
    @Column(name = "replaced_by_token_hash", length = 64)
    var replacedByTokenHash: String? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
) {
    fun isActiveAt(now: Instant): Boolean = usedAt == null && revokedAt == null && expiresAt.isAfter(now)

    fun rotate(now: Instant, replacementHash: String) {
        usedAt = now
        replacedByTokenHash = replacementHash
    }

    fun revoke(now: Instant) {
        if (revokedAt == null) revokedAt = now
    }
}
