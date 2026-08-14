package com.example.mypasture.auth.infrastructure

import com.example.mypasture.auth.domain.PasswordResetTokenEntity
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/** 비밀번호 재설정 토큰을 조회하고 이전 토큰을 원자적으로 폐기하는 repository입니다. */
@Repository
interface PasswordResetTokenRepository : JpaRepository<PasswordResetTokenEntity, UUID> {
    fun findByTokenHash(tokenHash: String): PasswordResetTokenEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select token from PasswordResetTokenEntity token where token.tokenHash = :tokenHash")
    fun findByTokenHashForUpdate(@Param("tokenHash") tokenHash: String): PasswordResetTokenEntity?

    fun findTopByUserIdOrderByCreatedAtDesc(userId: UUID): PasswordResetTokenEntity?

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update PasswordResetTokenEntity token
           set token.usedAt = :usedAt
         where token.user.id = :userId
           and token.usedAt is null
        """,
    )
    fun revokeAllActiveByUserId(
        @Param("userId") userId: UUID,
        @Param("usedAt") usedAt: Instant,
    ): Int
}
