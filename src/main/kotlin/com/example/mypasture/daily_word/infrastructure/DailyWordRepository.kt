package com.example.mypasture.daily_word.infrastructure

import com.example.mypasture.daily_word.domain.DailyWordEntity
import com.example.mypasture.daily_word.domain.DailyWordGenerationJobEntity
import com.example.mypasture.daily_word.domain.DailyWordGenerationJobStatus
import com.example.mypasture.daily_word.domain.DailyWordStatus
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** 날짜별 오늘의 말씀과 검수 상태를 잠금 기반으로 조회하는 repository입니다. */
interface DailyWordRepository : JpaRepository<DailyWordEntity, UUID> {
    fun findByWordDateAndStatus(wordDate: LocalDate, status: DailyWordStatus): DailyWordEntity?
    fun findByWordDate(wordDate: LocalDate): DailyWordEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select word from DailyWordEntity word where word.id = :id")
    fun findByIdForUpdate(id: UUID): DailyWordEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select word from DailyWordEntity word where word.wordDate = :wordDate")
    fun findByWordDateForUpdate(wordDate: LocalDate): DailyWordEntity?

    @Query(
        """
        select word from DailyWordEntity word
        where word.wordDate between :from and :to
          and (:status is null or word.status = :status)
        order by word.wordDate desc
        """,
    )
    fun findForAdmin(from: LocalDate, to: LocalDate, status: DailyWordStatus?): List<DailyWordEntity>
}

/** 생성 작업을 lease로 점유하고 만료된 워커 작업을 다시 회수하는 repository입니다. */
interface DailyWordGenerationJobRepository : JpaRepository<DailyWordGenerationJobEntity, UUID> {
    fun existsByWordDateAndStatusIn(wordDate: LocalDate, statuses: Collection<DailyWordGenerationJobStatus>): Boolean
    fun findAllByOrderByCreatedAtDesc(): List<DailyWordGenerationJobEntity>
    fun countByWordDateBetweenAndStatus(from: LocalDate, to: LocalDate, status: DailyWordGenerationJobStatus): Long
    fun findFirstByWordDateBetweenAndStatusInOrderByCreatedAtAsc(
        from: LocalDate,
        to: LocalDate,
        statuses: Collection<DailyWordGenerationJobStatus>,
    ): DailyWordGenerationJobEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select job from DailyWordGenerationJobEntity job
        where job.status = :pending
           or (job.status = :processing and job.leaseUntil < :now)
        order by job.createdAt
        """,
    )
    fun findClaimable(
        pending: DailyWordGenerationJobStatus,
        processing: DailyWordGenerationJobStatus,
        now: Instant,
        pageable: Pageable,
    ): List<DailyWordGenerationJobEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from DailyWordGenerationJobEntity job where job.id = :id")
    fun findByIdForUpdate(id: UUID): DailyWordGenerationJobEntity?
}
