package com.example.mypasture.prayer.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** 친구 요청과 차단 상태를 한 쌍의 사용자에게 영속적으로 보관합니다. */
@Entity
@Table(name = "prayer_friendships", uniqueConstraints = [UniqueConstraint(columnNames = ["user_low", "user_high"])])
class PrayerFriendshipEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "user_low", nullable = false) val userLow: UUID,
    @Column(name = "user_high", nullable = false) val userHigh: UUID,
    @Column(name = "requested_by", nullable = false) var requestedBy: UUID,
    @Column(nullable = false) var status: String = "PENDING",
    @Column(name = "blocked_by") var blockedBy: UUID? = null,
    @Column(name = "notify_low", nullable = false) var notifyLow: Boolean = false,
    @Column(name = "notify_high", nullable = false) var notifyHigh: Boolean = false,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now(),
) {
    fun other(userId: UUID): UUID = when (userId) {
        userLow -> userHigh
        userHigh -> userLow
        else -> throw IllegalArgumentException("Friendship is not owned by user")
    }

    fun notifies(userId: UUID): Boolean = when (userId) {
        userLow -> notifyLow
        userHigh -> notifyHigh
        else -> false
    }

    fun setNotifies(userId: UUID, enabled: Boolean) {
        when (userId) {
            userLow -> notifyLow = enabled
            userHigh -> notifyHigh = enabled
            else -> throw IllegalArgumentException("Friendship is not owned by user")
        }
    }
}

/** 대표 말씀과 일요일 알림 선택을 본인 계정에 연결합니다. */
@Entity
@Table(name = "prayer_profiles")
class PrayerProfileEntity(
    @Id @Column(name = "user_id") val userId: UUID,
    @Column(name = "representative_verse_id") var representativeVerseId: Long? = null,
    @Column(name = "sunday_reminder_enabled", nullable = false) var sundayReminderEnabled: Boolean = false,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now(),
)

/** 사용자의 한 주 기도제목과 친구 공개 선택을 저장합니다. */
@Entity
@Table(name = "weekly_prayer_topics", uniqueConstraints = [UniqueConstraint(columnNames = ["user_id", "week_start"])])
class WeeklyPrayerTopicEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "user_id", nullable = false) val userId: UUID,
    @Column(name = "week_start", nullable = false) val weekStart: LocalDate,
    @Column(nullable = false, length = 2000) var content: String,
    @Column(name = "visible_to_friends", nullable = false) var visibleToFriends: Boolean = false,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now(),
)

/** 한 사용자가 한 주에 한 친구에게 보낸 기도 편지입니다. */
@Entity
@Table(name = "prayer_letters", uniqueConstraints = [UniqueConstraint(columnNames = ["sender_id", "recipient_id", "week_start"])])
class PrayerLetterEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "topic_id", nullable = false) val topicId: UUID,
    @Column(name = "sender_id", nullable = false) val senderId: UUID,
    @Column(name = "recipient_id", nullable = false) val recipientId: UUID,
    @Column(name = "week_start", nullable = false) val weekStart: LocalDate,
    @Column(nullable = false, length = 2000) val content: String,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
)

/** AdMob 콜백 1회에 한 번만 발송권을 부여하는 광고 보상 원장입니다. */
@Entity
@Table(name = "prayer_ad_intents")
class PrayerAdIntentEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "user_id", nullable = false) val userId: UUID,
    @Column(name = "week_start", nullable = false) val weekStart: LocalDate,
    @Column(name = "reward_day", nullable = false) val rewardDay: LocalDate,
    @Column(name = "ad_unit_id", nullable = false) val adUnitId: String,
    @Column(name = "expires_at", nullable = false) val expiresAt: Instant,
    @Column(name = "claimed_at") var claimedAt: Instant? = null,
    @Column(name = "transaction_id", unique = true) var transactionId: String? = null,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
)

/** 편지와 새 기도제목 알림의 앱 내 기록 및 푸시 재시도 상태입니다. */
@Entity
@Table(name = "prayer_notifications")
class PrayerNotificationEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "event_key", unique = true, nullable = false) val eventKey: String,
    @Column(name = "user_id", nullable = false) val userId: UUID,
    @Column(nullable = false) val kind: String,
    @Column(name = "related_id") val relatedId: UUID? = null,
    @Column(nullable = false) val title: String,
    @Column(nullable = false) val body: String,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "read_at") var readAt: Instant? = null,
    @Column(name = "delivery_status", nullable = false) var deliveryStatus: String = "PENDING",
    @Column(name = "attempt_count", nullable = false) var attemptCount: Int = 0,
    @Column(name = "next_attempt_at", nullable = false) var nextAttemptAt: Instant = Instant.now(),
    @Column(name = "lease_until") var leaseUntil: Instant? = null,
)
