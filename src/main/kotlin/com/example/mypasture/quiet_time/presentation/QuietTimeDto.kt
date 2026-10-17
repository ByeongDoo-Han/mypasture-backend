package com.example.mypasture.quiet_time.presentation

import com.example.mypasture.quiet_time.domain.QuietTimePassageEntity
import com.example.mypasture.quiet_time.domain.QuietTimeParticipantEntity
import com.example.mypasture.quiet_time.domain.QuietTimePastureEntity
import com.example.mypasture.quiet_time.domain.QuietTimeSessionEntity
import com.example.mypasture.quiet_time.domain.QuietTimeReflectionEntity
import com.example.mypasture.quiet_time.application.QuietTimeJoinResult
import com.example.mypasture.quiet_time.application.QuietTimeQuestionAnswerView
import com.example.mypasture.quiet_time.application.QuietTimeSessionQrView
import com.example.mypasture.quiet_time.application.QuietTimePassageThemeView
import com.example.mypasture.quiet_time.application.QuietTimeQuestionSet
import com.example.mypasture.quiet_time.application.QuietTimeGenerationView
import com.example.mypasture.quiet_time.domain.QuietTimeQuestionCategory
import com.example.mypasture.quiet_time.domain.QuietTimeQuestionStyle
import jakarta.validation.constraints.NotBlank
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

/** 목자가 QT에 사용할 실제 성경 절 범위를 선택하는 요청입니다. */
data class SelectQuietTimePassageRequest(
    @field:NotBlank val version: String,
    @field:NotBlank val bookCode: String,
    val chapter: Int,
    val startVerse: Int,
    val endVerse: Int,
)

/** 직접 고른 말씀의 공용 질문 생성 요청입니다. */
data class GenerateQuietTimeQuestionsRequest(
    val style: QuietTimeQuestionStyle = QuietTimeQuestionStyle.BALANCED,
)

/** 완료·진행 중·실패 상태를 같은 계약으로 전달합니다. */
data class QuietTimeGenerationResponse(
    val status: String,
    val questionSet: QuietTimeQuestionSetResponse?,
)

fun QuietTimeGenerationView.toResponse() = QuietTimeGenerationResponse(status, questionSet?.toResponse())

/** 오프라인 큐티 세션 생성 요청 DTO입니다. */
data class CreateQuietTimeSessionRequest(
    val title: String? = null,
    val passageId: UUID? = null,
    val theme: String? = null,
    val focusThemeCode: String? = null,
    val questionStyle: QuietTimeQuestionStyle? = null,
    val meetingDate: LocalDate? = null,
    val meetingStartsAt: Instant? = null,
    val meetingEndsAt: Instant? = null,
    val joinStartsAt: Instant? = null,
)

/** 생성된 큐티 모임의 본문과 본문별 질문 구성을 변경하는 요청 DTO입니다. */
data class ChangeQuietTimePassageRequest(
    val passageId: UUID,
    val focusThemeCode: String? = null,
    val questionStyle: QuietTimeQuestionStyle? = null,
)

/** 선택한 본문과 초점 주제로 질문을 확정하기 전 미리 보는 요청입니다. */
data class PreviewQuietTimeQuestionSetRequest(
    @field:NotBlank
    @field:Size(max = 50)
    val focusThemeCode: String,
    val questionStyle: QuietTimeQuestionStyle = QuietTimeQuestionStyle.BALANCED,
)

/** 같은 큐티 세션의 기존 QR을 폐기하고 새 QR을 발급하는 요청 DTO입니다. */
data class ReissueQuietTimeQrRequest(
    val joinStartsAt: Instant? = null,
)

/** 사람이 읽을 수 있는 6자리 코드로 QT 초대 정보를 찾는 요청입니다. */
data class ResolveQuietTimeJoinCodeRequest(
    @field:NotBlank
    @field:Size(max = 20)
    val joinCode: String,
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

/** 한 본문에 연결된 주제의 우선순위와 근거 구간을 전달합니다. */
data class QuietTimePassageThemeResponse(
    val code: String,
    val name: String,
    val parentCode: String?,
    val description: String,
    val relevanceScore: Int,
    val primary: Boolean,
    val evidenceReference: String,
)

/** 리더가 확정하고 모든 참여자가 공유하는 버전 고정 질문 묶음입니다. */
data class QuietTimeQuestionSetResponse(
    val focusTheme: QuietTimePassageThemeResponse,
    val style: String,
    val version: Int,
    val observationQuestions: List<String>,
    val meditationQuestions: List<String>,
    val sharingQuestions: List<String>,
    val actionGuide: String,
    val prayerGuide: String,
    val lockedAt: Instant?,
)

/** QR로 공유되는 오프라인 큐티 세션 응답 DTO입니다. */
data class QuietTimeSessionResponse(
    val id: UUID,
    val scope: String,
    val pasture: QuietTimePastureResponse?,
    val hostDisplayName: String,
    val passage: QuietTimePassageResponse,
    val questionSet: QuietTimeQuestionSetResponse,
    val title: String,
    val meetingDate: LocalDate,
    val meetingStartsAt: Instant,
    val meetingEndsAt: Instant,
    val offlineCode: String,
    val joinCode: String,
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
        reference = if (startChapter == endChapter) {
            "$bookName $startChapter:$startVerse-$endVerse"
        } else {
            "$bookName $startChapter:$startVerse-$endChapter:$endVerse"
        },
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
        scope = session.scope.name,
        pasture = session.pasture?.toResponse(),
        hostDisplayName = session.hostDisplayName,
        passage = session.passage.toResponse(),
        questionSet = QuietTimeQuestionSetResponse(
            focusTheme = QuietTimePassageThemeResponse(
                code = session.focusThemeCode,
                name = session.focusThemeName,
                parentCode = null,
                description = "세션 생성 시 리더가 확정한 초점 주제입니다.",
                relevanceScore = 100,
                primary = true,
                evidenceReference = session.passage.toResponse().reference,
            ),
            style = session.questionStyle.name,
            version = session.questionSetVersion,
            observationQuestions = session.observationQuestions.toListValue("|"),
            meditationQuestions = session.meditationQuestions.toListValue("|"),
            sharingQuestions = session.sharingQuestions.toListValue("|"),
            actionGuide = session.actionGuide,
            prayerGuide = session.prayerGuide,
            lockedAt = session.questionSetLockedAt,
        ),
        title = session.title,
        meetingDate = session.meetingDate,
        meetingStartsAt = session.meetingStartsAt,
        meetingEndsAt = session.meetingEndsAt,
        offlineCode = invitation.offlineCode,
        joinCode = invitation.joinCode,
        inviteUrl = invitation.inviteUrl,
        joinStartsAt = invitation.joinStartsAt,
        expiresAt = invitation.expiresAt,
        revokedAt = invitation.revokedAt,
        status = qrStatus.name,
        sessionStatus = session.status.name,
        qrStatus = qrStatus.name,
        createdAt = session.createdAt,
    )

fun QuietTimePassageThemeView.toResponse() = QuietTimePassageThemeResponse(
    code = code,
    name = name,
    parentCode = parentCode,
    description = description,
    relevanceScore = relevanceScore,
    primary = primary,
    evidenceReference = evidenceReference,
)

fun QuietTimeQuestionSet.toResponse() = QuietTimeQuestionSetResponse(
    focusTheme = focusTheme.toResponse(),
    style = style.name,
    version = version,
    observationQuestions = observationQuestions,
    meditationQuestions = meditationQuestions,
    sharingQuestions = sharingQuestions,
    actionGuide = actionGuide,
    prayerGuide = prayerGuide,
    lockedAt = null,
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

/** QT 질문 하나에 게시할 답변 요청입니다. */
data class SaveQuietTimeQuestionAnswerRequest(
    @field:NotBlank
    @field:Size(max = 2000)
    val content: String,
)

/** 참여자들에게 실시간으로 공유되는 QT 질문별 답변입니다. */
data class QuietTimeQuestionAnswerResponse(
    val id: UUID,
    val sessionId: UUID,
    val userId: UUID?,
    val authorDisplayName: String,
    val questionCategory: QuietTimeQuestionCategory,
    val questionIndex: Int,
    val questionText: String,
    val content: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

fun QuietTimeQuestionAnswerView.toResponse() =
    QuietTimeQuestionAnswerResponse(
        id = id,
        sessionId = sessionId,
        userId = userId,
        authorDisplayName = authorDisplayName,
        questionCategory = questionCategory,
        questionIndex = questionIndex,
        questionText = questionText,
        content = content,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
