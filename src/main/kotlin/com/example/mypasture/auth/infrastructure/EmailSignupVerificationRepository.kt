package com.example.mypasture.auth.infrastructure

import com.example.mypasture.auth.domain.EmailSignupVerificationEntity
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/** 이메일별 인증 상태를 잠금 조회해 재전송과 가입 토큰 소비를 직렬화합니다. */
@Repository
interface EmailSignupVerificationRepository : JpaRepository<EmailSignupVerificationEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select verification from EmailSignupVerificationEntity verification where verification.email = :email")
    fun findByEmailForUpdate(@Param("email") email: String): EmailSignupVerificationEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "select verification from EmailSignupVerificationEntity verification " +
            "where verification.verificationTokenHash = :tokenHash",
    )
    fun findByVerificationTokenHashForUpdate(
        @Param("tokenHash") tokenHash: String,
    ): EmailSignupVerificationEntity?

    @Modifying
    @Query(
        """
        delete from EmailSignupVerificationEntity verification
         where verification.consumedAt < :cutoff
            or (verification.expiresAt < :cutoff
                and (verification.verificationTokenExpiresAt is null
                     or verification.verificationTokenExpiresAt < :cutoff))
        """,
    )
    fun deleteExpiredBefore(@Param("cutoff") cutoff: Instant): Int
}
