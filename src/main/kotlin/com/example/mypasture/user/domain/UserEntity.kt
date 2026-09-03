package com.example.mypasture.user.domain

import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** 사용자 권한과 관리자/목장 리더 역할을 표현하는 enum입니다. */
enum class UserRole { USER, PREMIUM, PASTURE_LEADER, ADMIN }

/** AI quota와 유료 기능 정책에 사용하는 구독 등급 enum입니다. */
enum class SubscriptionTier { FREE, PREMIUM }

/** 사용자 계정의 핵심 프로필, 권한, 구독 정보를 저장하는 JPA entity입니다. */
@Entity
@Table(name = "users")
class UserEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    var email: String? = null,
    var displayName: String,
    @Enumerated(EnumType.STRING)
    var role: UserRole = UserRole.USER,
    @Enumerated(EnumType.STRING)
    var subscriptionTier: SubscriptionTier = SubscriptionTier.FREE,
    var authVersion: Int = 0,
    var deletedAt: Instant? = null,
    val createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
) {
    /** 표시 이름만 변경하며 로그인 식별자와 기존 인증 세션은 유지합니다. */
    fun updateDisplayName(newDisplayName: String, now: Instant) {
        displayName = newDisplayName
        updatedAt = now
    }

    /** 비밀번호 변경처럼 기존 로그인 세션을 모두 종료해야 할 때 인증 버전을 증가시킵니다. */
    fun invalidateSessions(now: Instant) {
        authVersion += 1
        updatedAt = now
    }

    /** 탈퇴 사용자의 직접 식별 정보와 권한을 즉시 제거하면서 참조 무결성을 유지합니다. */
    fun withdraw(now: Instant) {
        invalidateSessions(now)
        email = null
        displayName = "탈퇴한 사용자"
        role = UserRole.USER
        subscriptionTier = SubscriptionTier.FREE
        deletedAt = now
        updatedAt = now
    }
}
