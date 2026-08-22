package com.example.mypasture.quiet_time.infrastructure

import com.example.mypasture.quiet_time.domain.QuietTimePassageEntity
import com.example.mypasture.quiet_time.domain.QuietTimePastureEntity
import com.example.mypasture.quiet_time.domain.QuietTimeParticipantEntity
import com.example.mypasture.quiet_time.domain.QuietTimeQrInvitationEntity
import com.example.mypasture.quiet_time.domain.QuietTimeSessionEntity
import com.example.mypasture.quiet_time.domain.QuietTimeReflectionEntity
import com.example.mypasture.quiet_time.domain.QuietTimeQuestionAnswerEntity
import com.example.mypasture.quiet_time.domain.QuietTimeQuestionCategory
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/** 큐티 본문 단락과 추천 기준 점수를 조회하고 저장하는 JPA repository입니다. */
@Repository
interface QuietTimePassageRepository : JpaRepository<QuietTimePassageEntity, UUID> {
    fun findByActiveTrue(): List<QuietTimePassageEntity>

    fun countByActiveTrue(): Long
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
}

/** QR 초대 코드의 발급 이력과 서버 시간 기준 유효기간을 관리하는 JPA repository입니다. */
@Repository
interface QuietTimeQrInvitationRepository : JpaRepository<QuietTimeQrInvitationEntity, UUID> {
    fun findByOfflineCode(offlineCode: String): QuietTimeQrInvitationEntity?

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
    fun findBySessionIdAndUserIdAndQuestionCategoryAndQuestionIndex(
        sessionId: UUID,
        userId: UUID,
        questionCategory: QuietTimeQuestionCategory,
        questionIndex: Int,
    ): QuietTimeQuestionAnswerEntity?

    fun findAllBySessionIdOrderByQuestionCategoryAscQuestionIndexAscUpdatedAtAsc(
        sessionId: UUID,
    ): List<QuietTimeQuestionAnswerEntity>
}
