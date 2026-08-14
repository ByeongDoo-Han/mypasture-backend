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

/** 원문을 저장하지 않는 일회성 비밀번호 재설정 토큰의 수명과 사용 상태를 관리합니다. */
@Entity
@Table(name = "password_reset_tokens")
class PasswordResetTokenEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    val user: UserEntity,
    @Column(name = "token_hash", length = 64, nullable = false, unique = true)
    val tokenHash: String,
    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,
    @Column(name = "used_at")
    var usedAt: Instant? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
) {
    fun isUsableAt(now: Instant): Boolean = usedAt == null && expiresAt.isAfter(now)

    fun markUsed(now: Instant) {
        usedAt = now
    }
}
