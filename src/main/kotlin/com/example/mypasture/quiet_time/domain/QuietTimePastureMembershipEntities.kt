package com.example.mypasture.quiet_time.domain

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

enum class QuietTimePastureRole { LEADER, MEMBER }
enum class QuietTimePastureJoinStatus { PENDING, APPROVED, REJECTED, EXPIRED }

/** 계정당 한 고정 목장만 허용하는 활성 소속입니다. */
@Entity
@Table(name = "quiet_time_pasture_memberships")
class QuietTimePastureMembershipEntity(
    @Id
    val userId: UUID,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pasture_id")
    val pasture: QuietTimePastureEntity,
    @Enumerated(EnumType.STRING)
    val role: QuietTimePastureRole,
    val joinedAt: Instant = Instant.now(),
)

/** 일 분 동안만 사용할 수 있는 목장 소속 초대 코드의 해시를 저장합니다. */
@Entity
@Table(name = "quiet_time_pasture_invites")
class QuietTimePastureInviteEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pasture_id")
    val pasture: QuietTimePastureEntity,
    val createdByUserId: UUID,
    @Column(length = 64, unique = true)
    val codeHash: String,
    val expiresAt: Instant,
    val createdAt: Instant = Instant.now(),
)

/** 본명은 승인 대기 중에만 보관하고 처리 즉시 지우는 가입 신청입니다. */
@Entity
@Table(name = "quiet_time_pasture_join_requests")
class QuietTimePastureJoinRequestEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pasture_id")
    val pasture: QuietTimePastureEntity,
    val userId: UUID,
    var activeUserId: UUID? = userId,
    @Column(length = 80)
    var realName: String?,
    @Enumerated(EnumType.STRING)
    var status: QuietTimePastureJoinStatus = QuietTimePastureJoinStatus.PENDING,
    val createdAt: Instant = Instant.now(),
    val expiresAt: Instant,
    var decidedAt: Instant? = null,
) {
    fun decide(nextStatus: QuietTimePastureJoinStatus, now: Instant) {
        require(status == QuietTimePastureJoinStatus.PENDING)
        require(nextStatus != QuietTimePastureJoinStatus.PENDING)
        status = nextStatus
        activeUserId = null
        realName = null
        decidedAt = now
    }
}
