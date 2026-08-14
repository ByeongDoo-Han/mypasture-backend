package com.example.mypasture.auth.domain

import com.example.mypasture.user.domain.UserEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** 로그인 제공자 종류를 표현하는 enum입니다. */
enum class AuthProvider { EMAIL, GOOGLE, APPLE, KAKAO }

/** 사용자와 로그인 제공자 계정을 연결하고 비밀번호 해시를 저장하는 JPA entity입니다. */
@Entity
@Table(name = "auth_accounts")
class AuthAccountEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    val user: UserEntity,
    @Enumerated(EnumType.STRING)
    val provider: AuthProvider,
    @Column(name = "provider_user_id")
    val providerUserId: String,
    @Column(name = "password_hash")
    var passwordHash: String? = null,
    val createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
) {
    /** 재설정된 비밀번호 해시와 변경 시각을 함께 갱신합니다. */
    fun changePassword(newPasswordHash: String, now: Instant) {
        passwordHash = newPasswordHash
        updatedAt = now
    }
}
