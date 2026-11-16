package com.example.mypasture.quiet_time.infrastructure

import com.example.mypasture.quiet_time.domain.QuietTimePastureInviteEntity
import com.example.mypasture.quiet_time.domain.QuietTimePastureJoinRequestEntity
import com.example.mypasture.quiet_time.domain.QuietTimePastureJoinStatus
import com.example.mypasture.quiet_time.domain.QuietTimePastureMembershipEntity
import jakarta.persistence.LockModeType
import java.time.Instant
import java.util.UUID
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

/** 사용자당 단일 목장 소속을 조회합니다. */
@Repository
interface QuietTimePastureMembershipRepository : JpaRepository<QuietTimePastureMembershipEntity, UUID> {
    @EntityGraph(attributePaths = ["pasture"])
    fun findByUserId(userId: UUID): QuietTimePastureMembershipEntity?
}

/** 단기 초대 코드를 해시로만 조회합니다. */
@Repository
interface QuietTimePastureInviteRepository : JpaRepository<QuietTimePastureInviteEntity, UUID> {
    @EntityGraph(attributePaths = ["pasture"])
    fun findByCodeHash(codeHash: String): QuietTimePastureInviteEntity?

    fun countByCreatedByUserIdAndCreatedAtAfter(createdByUserId: UUID, createdAt: Instant): Long
}

/** 승인 대기 신청을 신청자와 목장별로 조회합니다. */
@Repository
interface QuietTimePastureJoinRequestRepository : JpaRepository<QuietTimePastureJoinRequestEntity, UUID> {
    @EntityGraph(attributePaths = ["pasture"])
    fun findByActiveUserId(activeUserId: UUID): QuietTimePastureJoinRequestEntity?

    @EntityGraph(attributePaths = ["pasture"])
    fun findAllByPastureIdAndStatusOrderByCreatedAtAsc(pastureId: UUID, status: QuietTimePastureJoinStatus): List<QuietTimePastureJoinRequestEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = ["pasture"])
    @Query("select request from QuietTimePastureJoinRequestEntity request where request.id = :id")
    fun findByIdForUpdate(@Param("id") id: UUID): QuietTimePastureJoinRequestEntity?

    fun findAllByStatusAndExpiresAtBefore(status: QuietTimePastureJoinStatus, expiresAt: Instant): List<QuietTimePastureJoinRequestEntity>
}
