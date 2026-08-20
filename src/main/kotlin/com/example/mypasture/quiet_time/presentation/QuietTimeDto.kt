package com.example.mypasture.quiet_time.presentation

import com.example.mypasture.quiet_time.domain.QuietTimePassageEntity
import com.example.mypasture.quiet_time.domain.QuietTimeParticipantEntity
import com.example.mypasture.quiet_time.domain.QuietTimePastureEntity
import com.example.mypasture.quiet_time.domain.QuietTimeSessionEntity
import com.example.mypasture.quiet_time.domain.QuietTimeReflectionEntity
import com.example.mypasture.quiet_time.application.QuietTimeJoinResult
import com.example.mypasture.quiet_time.application.QuietTimeSessionQrView
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** 큐티목장 생성 요청 DTO입니다. */
data class CreateQuietTimePastureRequest(
    @field:Size(min = 2, max = 80)
    val name: String,
    @field:Size(min = 2, max = 80)
    val leaderName: String,
)

/** 오프라인 큐티 세션 생성 요청 DTO입니다. */
data class CreateQuietTimeSessionRequest(
    val title: String? = null,
    val passageId: UUID? = null,
    val theme: String? = null,
    val meetingDate: LocalDate? = null,
    val meetingStartsAt: Instant? = null,
    val meetingEndsAt: Instant? = null,
    val joinStartsAt: Instant? = null,
)

/** 같은 큐티 세션의 기존 QR을 폐기하고 새 QR을 발급하는 요청 DTO입니다. */
data class ReissueQuietTimeQrRequest(
    val joinStartsAt: Instant? = null,
)


/** QR 스캔 후 오프라인 큐티 세션 참여 요청 DTO입니다. */
data class JoinQuietTimeSessionRequest(
    @field:Size(min = 1, max = 40)
    val displayName: String,
    @field:Size(min = 8, max = 120)
    val deviceId: String,
)

/** 큐티 본문 추천 응답 DTO입니다. */
data class QuietTimePassageResponse(
    val id: UUID,
    val title: String,
    val reference: String,
    val bibleVersion: String,
    val bookCode: String,
    val bookName: String,
    val startChapter: Int,
    val startVerse: Int,
    val endChapter: Int,
    val endVerse: Int,
    val themeTags: List<String>,
    val criteria: QuietTimeCriteriaResponse,
    val sensitivityLevel: String,
    val recommendedGroupSize: String,
    val estimatedMinutes: Int,
    val observationQuestions: List<String>,
    val meditationQuestions: List<String>,
    val sharingQuestions: List<String>,
    val actionGuide: String,
    val prayerGuide: String,
)

/** 큐티 본문 선정 기준 점수 응답 DTO입니다. */
data class QuietTimeCriteriaResponse(
    val readabilityScore: Int,
    val contextScore: Int,
    val sharingScore: Int,
    val applicationScore: Int,
    val beginnerScore: Int,
    val totalScore: Int,
)

/** 큐티목장 응답 DTO입니다. */
data class QuietTimePastureResponse(
    val id: UUID,
    val name: String,
    val leaderName: String,
    val inviteCode: String,
    val createdAt: Instant,
)

/** QR로 공유되는 오프라인 큐티 세션 응답 DTO입니다. */
data class QuietTimeSessionResponse(
    val id: UUID,
    val pasture: QuietTimePastureResponse,
    val passage: QuietTimePassageResponse,
    val title: String,
    val meetingDate: LocalDate,
    val meetingStartsAt: Instant,
    val meetingEndsAt: Instant,
    val offlineCode: String,
    val inviteUrl: String,
    val joinStartsAt: Instant,
    val expiresAt: Instant,
    val revokedAt: Instant?,
    val status: String,
    val sessionStatus: String,
    val qrStatus: String,
    val createdAt: Instant,
)

/** QR 스캔으로 참여한 큐티 세션 참여자 응답 DTO입니다. */
data class QuietTimeParticipantResponse(
    val id: UUID,
    val displayName: String,
    val joinedAt: Instant,
)

/** QR 스캔 후 큐티 세션 참여 완료 응답 DTO입니다. */
data class JoinQuietTimeSessionResponse(
    val session: QuietTimeSessionResponse,
    val participant: QuietTimeParticipantResponse,
    val joinedAlready: Boolean,
    val participantCount: Long,
)

fun QuietTimePassageEntity.toResponse(): QuietTimePassageResponse =
    QuietTimePassageResponse(
        id = id,
        title = title,
        reference = "$bookName $startChapter:$startVerse-$endChapter:$endVerse",
        bibleVersion = bibleVersion,
        bookCode = bookCode,
        bookName = bookName,
        startChapter = startChapter,
        startVerse = startVerse,
        endChapter = endChapter,
        endVerse = endVerse,
        themeTags = themeTags.toListValue(),
        criteria = QuietTimeCriteriaResponse(
            readabilityScore = readabilityScore,
            contextScore = contextScore,
            sharingScore = sharingScore,
            applicationScore = applicationScore,
            beginnerScore = beginnerScore,
            totalScore = readabilityScore + contextScore + sharingScore + applicationScore + beginnerScore,
        ),
        sensitivityLevel = sensitivityLevel.name,
        recommendedGroupSize = recommendedGroupSize.name,
        estimatedMinutes = estimatedMinutes,
        observationQuestions = observationQuestions.toListValue("|"),
        meditationQuestions = meditationQuestions.toListValue("|"),
        sharingQuestions = sharingQuestions.toListValue("|"),
        actionGuide = actionGuide,
        prayerGuide = prayerGuide,
    )

fun QuietTimePastureEntity.toResponse(): QuietTimePastureResponse =
    QuietTimePastureResponse(id = id, name = name, leaderName = leaderName, inviteCode = inviteCode, createdAt = createdAt)

fun QuietTimeSessionQrView.toResponse(): QuietTimeSessionResponse =
    QuietTimeSessionResponse(
        id = session.id,
        pasture = session.pasture.toResponse(),
        passage = session.passage.toResponse(),
        title = session.title,
        meetingDate = session.meetingDate,
        meetingStartsAt = session.meetingStartsAt,
        meetingEndsAt = session.meetingEndsAt,
        offlineCode = invitation.offlineCode,
        inviteUrl = invitation.inviteUrl,
        joinStartsAt = invitation.joinStartsAt,
        expiresAt = invitation.expiresAt,
        revokedAt = invitation.revokedAt,
        status = qrStatus.name,
        sessionStatus = session.status.name,
        qrStatus = qrStatus.name,
        createdAt = session.createdAt,
    )

private fun String.toListValue(separator: String = ","): List<String> =
    split(separator).map { it.trim() }.filter { it.isNotBlank() }

fun QuietTimeParticipantEntity.toResponse(): QuietTimeParticipantResponse =
    QuietTimeParticipantResponse(id = id, displayName = displayName, joinedAt = joinedAt)

fun QuietTimeJoinResult.toResponse(): JoinQuietTimeSessionResponse =
    JoinQuietTimeSessionResponse(
        session = sessionView.toResponse(),
        participant = participant.toResponse(),
        joinedAlready = joinedAlready,
        participantCount = participantCount,
    )

/** 현장 QT 묵상을 저장하고 목장 공유 여부를 지정하는 요청입니다. */
data class SaveQuietTimeReflectionRequest(
    @field:Size(min = 1, max = 3000)
    val content: String,
    val sharedWithPasture: Boolean = false,
)

data class QuietTimeReflectionResponse(
    val id: UUID,
    val sessionId: UUID,
    val userId: UUID,
    val content: String,
    val sharedWithPasture: Boolean,
    val updatedAt: Instant,
)

fun QuietTimeReflectionEntity.toResponse() = QuietTimeReflectionResponse(id, session.id, userId, content, sharedWithPasture, updatedAt)
