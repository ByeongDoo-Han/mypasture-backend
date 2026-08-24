package com.example.mypasture.daily_word.infrastructure

import com.example.mypasture.daily_word.domain.DailyWordIncidentEmailOutboxEntity
import com.example.mypasture.daily_word.domain.DailyWordIncidentEmailStatus
import com.example.mypasture.daily_word.domain.DailyWordIncidentEntity
import com.example.mypasture.daily_word.domain.DailyWordIncidentStatus
import com.example.mypasture.daily_word.domain.DailyWordIncidentType
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Page
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** 날짜·유형별 운영 사건을 잠금 조회하고 최신순으로 제공합니다. */
interface DailyWordIncidentRepository : JpaRepository<DailyWordIncidentEntity, UUID> {
    fun findTop100ByOrderByLastDetectedAtDesc(): List<DailyWordIncidentEntity>
    fun findTop100ByStatusOrderByLastDetectedAtDesc(status: DailyWordIncidentStatus): List<DailyWordIncidentEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select incident from DailyWordIncidentEntity incident where incident.id = :id")
    fun findByIdForUpdate(id: UUID): DailyWordIncidentEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select incident from DailyWordIncidentEntity incident
        where incident.type = :type and incident.wordDate = :wordDate
        """,
    )
    fun findByTypeAndWordDateForUpdate(type: DailyWordIncidentType, wordDate: LocalDate): DailyWordIncidentEntity?
}

/** 관리자 사건 이메일 outbox를 중복 없이 예약하고 lease 기반으로 선점합니다. */
interface DailyWordIncidentEmailOutboxRepository : JpaRepository<DailyWordIncidentEmailOutboxEntity, UUID> {
    fun existsByIncident_IdAndNotificationVersionAndRecipientEmail(
        incidentId: UUID,
        notificationVersion: Int,
        recipientEmail: String,
    ): Boolean

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select delivery from DailyWordIncidentEmailOutboxEntity delivery
        join fetch delivery.incident incident
        where (
          delivery.status in :readyStatuses and delivery.nextAttemptAt <= :now
        ) or (
          delivery.status = :processingStatus and delivery.leaseUntil <= :now
        )
        order by delivery.nextAttemptAt, delivery.createdAt
        """,
    )
    fun findClaimable(
        readyStatuses: Collection<DailyWordIncidentEmailStatus>,
        processingStatus: DailyWordIncidentEmailStatus,
        now: Instant,
        pageable: Pageable,
    ): List<DailyWordIncidentEmailOutboxEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select delivery from DailyWordIncidentEmailOutboxEntity delivery where delivery.id = :id")
    fun findByIdForUpdate(id: UUID): DailyWordIncidentEmailOutboxEntity?

    @Query(
        """
        select delivery from DailyWordIncidentEmailOutboxEntity delivery
        join fetch delivery.incident
        where delivery.id = :id
        """,
    )
    fun findByIdWithIncident(id: UUID): DailyWordIncidentEmailOutboxEntity?

    @Query(
        value = """
        select delivery from DailyWordIncidentEmailOutboxEntity delivery
        join fetch delivery.incident incident
        where (:status is null or delivery.status = :status)
          and (:incidentId is null or incident.id = :incidentId)
          and delivery.createdAt >= :from
          and delivery.createdAt < :to
        order by delivery.createdAt desc
        """,
        countQuery = """
        select count(delivery) from DailyWordIncidentEmailOutboxEntity delivery
        where (:status is null or delivery.status = :status)
          and (:incidentId is null or delivery.incident.id = :incidentId)
          and delivery.createdAt >= :from
          and delivery.createdAt < :to
        """,
    )
    fun findForAdmin(
        status: DailyWordIncidentEmailStatus?,
        incidentId: UUID?,
        from: Instant,
        to: Instant,
        pageable: Pageable,
    ): Page<DailyWordIncidentEmailOutboxEntity>

    @Query(
        """
        select delivery.status as status, count(delivery) as total
        from DailyWordIncidentEmailOutboxEntity delivery
        where (:incidentId is null or delivery.incident.id = :incidentId)
          and delivery.createdAt >= :from
          and delivery.createdAt < :to
        group by delivery.status
        """,
    )
    fun summarizeForAdmin(incidentId: UUID?, from: Instant, to: Instant): List<DailyWordIncidentEmailStatusCount>
}

/** 이메일 delivery 상태별 집계 쿼리 결과입니다. */
interface DailyWordIncidentEmailStatusCount {
    val status: DailyWordIncidentEmailStatus
    val total: Long
}
