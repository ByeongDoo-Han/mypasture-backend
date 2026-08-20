package com.example.mypasture.auth.infrastructure

import com.example.mypasture.auth.domain.SocialLoginChallengeEntity
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/** social login nonce를 단 한 번 소비하도록 행 잠금 조회를 제공합니다. */
@Repository
interface SocialLoginChallengeRepository : JpaRepository<SocialLoginChallengeEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select challenge from SocialLoginChallengeEntity challenge where challenge.id = :id")
    fun findByIdForUpdate(@Param("id") id: UUID): SocialLoginChallengeEntity?

    @Modifying
    @Query("delete from SocialLoginChallengeEntity challenge where challenge.expiresAt < :cutoff")
    fun deleteExpiredBefore(@Param("cutoff") cutoff: Instant): Int
}
