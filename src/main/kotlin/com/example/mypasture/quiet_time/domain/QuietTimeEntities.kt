package com.example.mypasture.quiet_time.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** 큐티 본문의 난이도와 주의 필요 수준을 표현하는 enum입니다. */
enum class QuietTimeSensitivityLevel { LOW, MEDIUM, HIGH }

/** 큐티 본문을 추천하기 좋은 모임 규모를 표현하는 enum입니다. */
enum class QuietTimeGroupSize { INDIVIDUAL, SMALL_GROUP, PASTURE }

/** QT 모임이 고정 목장에 속하는지 일회성 모임인지 구분합니다. */
enum class QuietTimeSessionScope { PASTURE, STANDALONE }

/** 큐티목장 오프라인 세션의 진행 상태를 표현하는 enum입니다. */
enum class QuietTimeSessionStatus { DRAFT, OPEN, CLOSED }

/** QR 초대 코드의 서버 시간 기준 참여 가능 상태입니다. */
enum class QuietTimeQrStatus { SCHEDULED, OPEN, EXPIRED, REVOKED, CLOSED }

/** QT 질문이 묵상, 나눔, 실천 중 어느 진행 목적을 더 강조하는지 표현합니다. */
enum class QuietTimeQuestionStyle { BALANCED, REFLECTION, SHARING, ACTION, NEW_BELIEVER }

/** 큐티에 사용할 성경 본문 단락과 추천 기준 점수를 저장하는 JPA entity입니다. */
@Entity
@Table(name = "quiet_time_passages")
class QuietTimePassageEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    val title: String,
    val bibleVersion: String,
    val bookCode: String,
    val bookName: String,
    val startChapter: Int,
    val startVerse: Int,
    val endChapter: Int,
    val endVerse: Int,
    @Column(columnDefinition = "text")
    val themeTags: String,
    val readabilityScore: Int,
    val contextScore: Int,
    val sharingScore: Int,
    val applicationScore: Int,
    val beginnerScore: Int,
    @Enumerated(EnumType.STRING)
    val sensitivityLevel: QuietTimeSensitivityLevel,
    @Enumerated(EnumType.STRING)
    val recommendedGroupSize: QuietTimeGroupSize,
    val estimatedMinutes: Int,
    @Column(columnDefinition = "text")
    val observationQuestions: String,
    @Column(columnDefinition = "text")
    val meditationQuestions: String,
    @Column(columnDefinition = "text")
    val sharingQuestions: String,
    @Column(columnDefinition = "text")
    val actionGuide: String,
    @Column(columnDefinition = "text")
    val prayerGuide: String,
    val active: Boolean = true,
    val createdAt: Instant = Instant.now(),
)

/** 오프라인 큐티 모임을 여는 목장 정보를 저장하는 JPA entity입니다. */
@Entity
@Table(name = "quiet_time_pastures")
class QuietTimePastureEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    val name: String,
    val leaderName: String,
    val leaderUserId: UUID? = null,
    val inviteCode: String,
    val createdAt: Instant = Instant.now(),
)

/** QR로 공유되는 오프라인 큐티 세션과 선택된 본문을 저장하는 JPA entity입니다. */
@Entity
@Table(name = "quiet_time_sessions")
class QuietTimeSessionEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY)
    val pasture: QuietTimePastureEntity? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    var passage: QuietTimePassageEntity,
    @Enumerated(EnumType.STRING)
    val scope: QuietTimeSessionScope,
    val hostUserId: UUID? = null,
    val hostDisplayName: String,
    val title: String,
    val meetingDate: LocalDate,
    val meetingStartsAt: Instant,
    val meetingEndsAt: Instant,
    var focusThemeCode: String,
    var focusThemeName: String,
    @Enumerated(EnumType.STRING)
    var questionStyle: QuietTimeQuestionStyle,
    var questionSetVersion: Int,
    @Column(columnDefinition = "text")
    var observationQuestions: String,
    @Column(columnDefinition = "text")
    var meditationQuestions: String,
    @Column(columnDefinition = "text")
    var sharingQuestions: String,
    @Column(columnDefinition = "text")
    var actionGuide: String,
    @Column(columnDefinition = "text")
    var prayerGuide: String,
    var questionSetLockedAt: Instant,
    @Enumerated(EnumType.STRING)
    var status: QuietTimeSessionStatus = QuietTimeSessionStatus.OPEN,
    val createdAt: Instant = Instant.now(),
) {
    fun isManagedBy(userId: UUID): Boolean =
        when (scope) {
            QuietTimeSessionScope.PASTURE -> pasture?.leaderUserId == userId
            QuietTimeSessionScope.STANDALONE -> hostUserId == userId
        }

    fun managerDisplayName(): String =
        when (scope) {
            QuietTimeSessionScope.PASTURE -> pasture?.leaderName ?: hostDisplayName
            QuietTimeSessionScope.STANDALONE -> hostDisplayName
        }

    /** 참여가 시작되기 전 새 본문과 그 본문에 맞는 질문 스냅샷을 원자적으로 교체합니다. */
    fun changePassageAndQuestionSet(
        nextPassage: QuietTimePassageEntity,
        nextFocusThemeCode: String,
        nextFocusThemeName: String,
        nextQuestionStyle: QuietTimeQuestionStyle,
        nextQuestionSetVersion: Int,
        nextObservationQuestions: String,
        nextMeditationQuestions: String,
        nextSharingQuestions: String,
        nextActionGuide: String,
        nextPrayerGuide: String,
        changedAt: Instant,
    ) {
        passage = nextPassage
        focusThemeCode = nextFocusThemeCode
        focusThemeName = nextFocusThemeName
        questionStyle = nextQuestionStyle
        questionSetVersion = nextQuestionSetVersion
        observationQuestions = nextObservationQuestions
        meditationQuestions = nextMeditationQuestions
        sharingQuestions = nextSharingQuestions
        actionGuide = nextActionGuide
        prayerGuide = nextPrayerGuide
        questionSetLockedAt = changedAt
    }

    fun close() {
        status = QuietTimeSessionStatus.CLOSED
    }
}

/** 큐티 세션에서 발급한 QR 초대 코드의 기간과 폐기 이력을 저장하는 JPA entity입니다. */
@Entity
@Table(name = "quiet_time_qr_invitations")
class QuietTimeQrInvitationEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY)
    val session: QuietTimeSessionEntity,
    val offlineCode: String,
    @Column(length = 6)
    val joinCode: String,
    val inviteUrl: String,
    val joinStartsAt: Instant,
    val expiresAt: Instant,
    var revokedAt: Instant? = null,
    val createdAt: Instant = Instant.now(),
) {
    fun statusAt(now: Instant): QuietTimeQrStatus =
        when {
            session.status == QuietTimeSessionStatus.CLOSED -> QuietTimeQrStatus.CLOSED
            revokedAt != null -> QuietTimeQrStatus.REVOKED
            !expiresAt.isAfter(now) -> QuietTimeQrStatus.EXPIRED
            joinStartsAt.isAfter(now) -> QuietTimeQrStatus.SCHEDULED
            else -> QuietTimeQrStatus.OPEN
        }

    fun revoke(now: Instant) {
        if (revokedAt == null) revokedAt = now
    }
}

/** QR 스캔으로 오프라인 큐티 세션에 참여한 사용자를 기록하는 JPA entity입니다. */
@Entity
@Table(name = "quiet_time_session_participants")
class QuietTimeParticipantEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY)
    val session: QuietTimeSessionEntity,
    val displayName: String,
    val deviceId: String,
    var userId: UUID? = null,
    val joinedAt: Instant = Instant.now(),
)
