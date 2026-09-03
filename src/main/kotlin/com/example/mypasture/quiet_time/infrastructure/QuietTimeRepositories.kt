package com.example.mypasture.quiet_time.infrastructure

import com.example.mypasture.quiet_time.domain.QuietTimePassageEntity
import com.example.mypasture.quiet_time.domain.QuietTimePastureEntity
import com.example.mypasture.quiet_time.domain.QuietTimeParticipantEntity
import com.example.mypasture.quiet_time.domain.QuietTimeQrInvitationEntity
import com.example.mypasture.quiet_time.domain.QuietTimeSessionEntity
import com.example.mypasture.quiet_time.domain.QuietTimeSessionScope
import com.example.mypasture.quiet_time.domain.QuietTimeSessionStatus
import com.example.mypasture.quiet_time.domain.QuietTimeReflectionEntity
import com.example.mypasture.quiet_time.domain.QuietTimeQuestionAnswerEntity
import com.example.mypasture.quiet_time.domain.QuietTimeQuestionCategory
import com.example.mypasture.quiet_time.domain.QuietTimeThemeEntity
import com.example.mypasture.quiet_time.domain.QuietTimePassageThemeEntity
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** 큐티 본문 단락과 추천 기준 점수를 조회하고 저장하는 JPA repository입니다. */
@Repository
interface QuietTimePassageRepository : JpaRepository<QuietTimePassageEntity, UUID> {
    fun findByActiveTrue(): List<QuietTimePassageEntity>

    fun countByActiveTrue(): Long
}

/** 운영에서 관리하는 안정적인 QT 주제 코드를 조회합니다. */
@Repository
interface QuietTimeThemeRepository : JpaRepository<QuietTimeThemeEntity, UUID> {
    fun findByCodeAndActiveTrue(code: String): QuietTimeThemeEntity?
}

/** 본문에 연결된 복수 주제를 우선순위대로 조회합니다. */
@Repository
interface QuietTimePassageThemeRepository : JpaRepository<QuietTimePassageThemeEntity, UUID> {
    @EntityGraph(attributePaths = ["theme"])
    fun findAllByPassageIdOrderByPrimaryThemeDescRelevanceScoreDesc(passageId: UUID): List<QuietTimePassageThemeEntity>

    @EntityGraph(attributePaths = ["theme"])
    fun findByPassageIdAndThemeCode(passageId: UUID, themeCode: String): QuietTimePassageThemeEntity?
}

/** 큐티목장 정보와 리더 소유권을 조회하는 JPA repository입니다. */
@Repository
interface QuietTimePastureRepository : JpaRepository<QuietTimePastureEntity, UUID> {
    fun existsByInviteCode(inviteCode: String): Boolean

    fun findAllByLeaderUserIdOrderByCreatedAtDesc(leaderUserId: UUID): List<QuietTimePastureEntity>
}

/** 오프라인 큐티 세션을 조회하고 종료·재발급 요청을 직렬화하는 JPA repository입니다. */
@Repository
interface QuietTimeSessionRepository : JpaRepository<QuietTimeSessionEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from QuietTimeSessionEntity session where session.id = :sessionId")
    fun findByIdForUpdate(@Param("sessionId") sessionId: UUID): QuietTimeSessionEntity?

    @EntityGraph(attributePaths = ["pasture", "passage"])
    @Query(
        """
        select session
          from QuietTimeSessionEntity session
          left join session.pasture pasture
         where (:scope is null or session.scope = :scope)
           and (:status is null or session.status = :status)
           and (:fromDate is null or session.meetingDate >= :fromDate)
           and (:toDate is null or session.meetingDate <= :toDate)
           and (
             :search = ''
             or lower(session.title) like lower(concat('%', :search, '%'))
             or lower(session.hostDisplayName) like lower(concat('%', :search, '%'))
             or lower(pasture.name) like lower(concat('%', :search, '%'))
           )
        """,
    )
    fun findAdminPage(
        @Param("scope") scope: QuietTimeSessionScope?,
        @Param("status") status: QuietTimeSessionStatus?,
        @Param("fromDate") fromDate: LocalDate?,
        @Param("toDate") toDate: LocalDate?,
        @Param("search") search: String,
        pageable: Pageable,
    ): Page<QuietTimeSessionEntity>

    fun countByMeetingDateBetween(fromDate: LocalDate, toDate: LocalDate): Long

    fun countByMeetingDateBetweenAndStatus(
        fromDate: LocalDate,
        toDate: LocalDate,
        status: QuietTimeSessionStatus,
    ): Long

    fun countByMeetingDateBetweenAndScope(
        fromDate: LocalDate,
        toDate: LocalDate,
        scope: QuietTimeSessionScope,
    ): Long
}

/** QR 초대 코드의 발급 이력과 서버 시간 기준 유효기간을 관리하는 JPA repository입니다. */
@Repository
interface QuietTimeQrInvitationRepository : JpaRepository<QuietTimeQrInvitationEntity, UUID> {
    fun findByOfflineCode(offlineCode: String): QuietTimeQrInvitationEntity?

    fun findByJoinCode(joinCode: String): QuietTimeQrInvitationEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        select invitation
          from QuietTimeQrInvitationEntity invitation
          join fetch invitation.session session
          left join fetch session.pasture
          join fetch session.passage
         where invitation.offlineCode = :offlineCode
        """,
    )
    fun findByOfflineCodeForUpdate(@Param("offlineCode") offlineCode: String): QuietTimeQrInvitationEntity?

    fun existsByOfflineCode(offlineCode: String): Boolean

    fun existsByJoinCode(joinCode: String): Boolean

    @Query(
        """
        select invitation
          from QuietTimeQrInvitationEntity invitation
         where invitation.session.id = :sessionId
           and invitation.revokedAt is null
           and invitation.expiresAt > :now
         order by invitation.createdAt desc
        """,
    )
    fun findActiveBySessionId(
        @Param("sessionId") sessionId: UUID,
        @Param("now") now: Instant,
    ): List<QuietTimeQrInvitationEntity>

    fun findTopBySessionIdOrderByCreatedAtDesc(sessionId: UUID): QuietTimeQrInvitationEntity?
}

/** QR 스캔으로 큐티 세션에 참여한 사용자를 조회하고 저장하는 JPA repository입니다. */
@Repository
interface QuietTimeParticipantRepository : JpaRepository<QuietTimeParticipantEntity, UUID> {
    fun findBySessionIdAndDeviceId(sessionId: UUID, deviceId: String): QuietTimeParticipantEntity?

    fun findBySessionIdAndUserId(sessionId: UUID, userId: UUID): QuietTimeParticipantEntity?

    fun existsBySessionIdAndUserId(sessionId: UUID, userId: UUID): Boolean

    fun countBySessionId(sessionId: UUID): Long

    @Query(
        """
        select participant.session.id as sessionId, count(participant.id) as total
          from QuietTimeParticipantEntity participant
         where participant.session.id in :sessionIds
         group by participant.session.id
        """,
    )
    fun countGroupedBySessionIds(@Param("sessionIds") sessionIds: Collection<UUID>): List<QuietTimeSessionCountProjection>

    @Query(
        """
        select count(participant.id)
          from QuietTimeParticipantEntity participant
         where participant.session.meetingDate between :fromDate and :toDate
        """,
    )
    fun countBySessionMeetingDateBetween(
        @Param("fromDate") fromDate: LocalDate,
        @Param("toDate") toDate: LocalDate,
    ): Long
}

/** QT 묵상과 목장 공유 목록을 저장하는 repository입니다. */
@Repository
interface QuietTimeReflectionRepository : JpaRepository<QuietTimeReflectionEntity, UUID> {
    fun findBySessionIdAndUserId(sessionId: UUID, userId: UUID): QuietTimeReflectionEntity?
    fun findAllBySessionIdAndSharedWithPastureTrueOrderByUpdatedAtDesc(sessionId: UUID): List<QuietTimeReflectionEntity>
}

/** QT 세션의 질문별 최신 답변을 작성자와 질문 위치 기준으로 저장·조회합니다. */
@Repository
interface QuietTimeQuestionAnswerRepository : JpaRepository<QuietTimeQuestionAnswerEntity, UUID> {
    fun countBySessionId(sessionId: UUID): Long

    fun findBySessionIdAndUserIdAndQuestionCategoryAndQuestionIndex(
        sessionId: UUID,
        userId: UUID,
        questionCategory: QuietTimeQuestionCategory,
        questionIndex: Int,
    ): QuietTimeQuestionAnswerEntity?

    fun findAllBySessionIdOrderByQuestionCategoryAscQuestionIndexAscUpdatedAtAsc(
        sessionId: UUID,
    ): List<QuietTimeQuestionAnswerEntity>

    @Query(
        """
        select answer.session.id as sessionId, count(answer.id) as total
          from QuietTimeQuestionAnswerEntity answer
         where answer.session.id in :sessionIds
         group by answer.session.id
        """,
    )
    fun countGroupedBySessionIds(@Param("sessionIds") sessionIds: Collection<UUID>): List<QuietTimeSessionCountProjection>

    @Query(
        """
        select count(answer.id)
          from QuietTimeQuestionAnswerEntity answer
         where answer.session.meetingDate between :fromDate and :toDate
        """,
    )
    fun countBySessionMeetingDateBetween(
        @Param("fromDate") fromDate: LocalDate,
        @Param("toDate") toDate: LocalDate,
    ): Long
}

/** 관리자 목록의 참여자·답변 개수를 세션별로 묶어 반환하는 projection입니다. */
interface QuietTimeSessionCountProjection {
    val sessionId: UUID
    val total: Long
}
