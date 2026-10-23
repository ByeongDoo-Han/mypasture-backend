package com.example.mypasture.prayer.infrastructure

import com.example.mypasture.prayer.domain.PrayerAdIntentEntity
import com.example.mypasture.prayer.domain.PrayerFriendshipEntity
import com.example.mypasture.prayer.domain.PrayerLetterEntity
import com.example.mypasture.prayer.domain.PrayerNotificationEntity
import com.example.mypasture.prayer.domain.PrayerProfileEntity
import com.example.mypasture.prayer.domain.WeeklyPrayerTopicEntity
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import java.time.LocalDate
import java.util.UUID

/** 기도 기능의 사용자 설정 저장소입니다. */
interface PrayerProfileRepository : JpaRepository<PrayerProfileEntity, UUID> {
    fun findAllBySundayReminderEnabledTrue(): List<PrayerProfileEntity>
}

/** 두 계정 사이의 단일 친구 상태 저장소입니다. */
interface PrayerFriendshipRepository : JpaRepository<PrayerFriendshipEntity, UUID> {
    fun findByUserLowAndUserHigh(userLow: UUID, userHigh: UUID): PrayerFriendshipEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from PrayerFriendshipEntity f where f.userLow = :userLow and f.userHigh = :userHigh")
    fun findPairForUpdate(userLow: UUID, userHigh: UUID): PrayerFriendshipEntity?

    @Query("select f from PrayerFriendshipEntity f where f.userLow = :userId or f.userHigh = :userId order by f.updatedAt desc")
    fun findForUser(userId: UUID): List<PrayerFriendshipEntity>

    @Query("select f from PrayerFriendshipEntity f where f.status = 'ACCEPTED' and (f.userLow = :userId or f.userHigh = :userId)")
    fun findAcceptedForUser(userId: UUID): List<PrayerFriendshipEntity>
}

/** 주간 기도제목의 사용자·주차 중복을 방지하는 저장소입니다. */
interface WeeklyPrayerTopicRepository : JpaRepository<WeeklyPrayerTopicEntity, UUID> {
    fun findByUserIdAndWeekStart(userId: UUID, weekStart: LocalDate): WeeklyPrayerTopicEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select topic from WeeklyPrayerTopicEntity topic where topic.id = :id")
    fun findByIdForUpdate(id: UUID): WeeklyPrayerTopicEntity?
}

/** 발송된 편지의 사용자별 목록과 주간 인원수를 조회합니다. */
interface PrayerLetterRepository : JpaRepository<PrayerLetterEntity, UUID> {
    fun countBySenderIdAndWeekStart(senderId: UUID, weekStart: LocalDate): Long
    fun existsBySenderIdAndRecipientIdAndWeekStart(senderId: UUID, recipientId: UUID, weekStart: LocalDate): Boolean
    fun findBySenderIdOrderByCreatedAtDesc(senderId: UUID, pageable: Pageable): List<PrayerLetterEntity>
    fun findByRecipientIdOrderByCreatedAtDesc(recipientId: UUID, pageable: Pageable): List<PrayerLetterEntity>
}

/** 광고 보상 요청 및 SSV 거래 ID 중복을 조회합니다. */
interface PrayerAdIntentRepository : JpaRepository<PrayerAdIntentEntity, UUID> {
    fun countByUserIdAndRewardDayAndClaimedAtIsNotNull(userId: UUID, rewardDay: LocalDate): Long
    fun countByUserIdAndWeekStartAndClaimedAtIsNotNull(userId: UUID, weekStart: LocalDate): Long
    fun existsByTransactionId(transactionId: String): Boolean
}

/** 앱 내 알림과 푸시 대기 원장의 저장소입니다. */
interface PrayerNotificationRepository : JpaRepository<PrayerNotificationEntity, UUID> {
    fun existsByEventKey(eventKey: String): Boolean
    fun findByUserIdOrderByCreatedAtDesc(userId: UUID, pageable: Pageable): List<PrayerNotificationEntity>
    fun findByIdAndUserId(id: UUID, userId: UUID): PrayerNotificationEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from PrayerNotificationEntity n where (n.deliveryStatus = 'PENDING' or (n.deliveryStatus = 'PROCESSING' and n.leaseUntil <= :now)) and n.nextAttemptAt <= :now order by n.createdAt")
    fun findDueForUpdate(now: java.time.Instant, pageable: Pageable): List<PrayerNotificationEntity>
}
