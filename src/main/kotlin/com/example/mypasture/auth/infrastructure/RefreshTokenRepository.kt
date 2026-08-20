package com.example.mypasture.auth.infrastructure

import com.example.mypasture.auth.domain.RefreshTokenEntity
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/** refresh token 회전과 재사용 탐지에 필요한 잠금·폐기 연산을 제공합니다. */
@Repository
interface RefreshTokenRepository : JpaRepository<RefreshTokenEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select token from RefreshTokenEntity token join fetch token.user where token.tokenHash = :tokenHash")
    fun findByTokenHashForUpdate(@Param("tokenHash") tokenHash: String): RefreshTokenEntity?

    @Modifying
    @Query("update RefreshTokenEntity token set token.revokedAt = :now where token.familyId = :familyId and token.revokedAt is null")
    fun revokeFamily(@Param("familyId") familyId: UUID, @Param("now") now: Instant): Int

    @Modifying
    @Query("update RefreshTokenEntity token set token.revokedAt = :now where token.user.id = :userId and token.revokedAt is null")
    fun revokeAllByUserId(@Param("userId") userId: UUID, @Param("now") now: Instant): Int

    @Modifying
    @Query("delete from RefreshTokenEntity token where token.expiresAt < :cutoff")
    fun deleteExpiredBefore(@Param("cutoff") cutoff: Instant): Int
}
