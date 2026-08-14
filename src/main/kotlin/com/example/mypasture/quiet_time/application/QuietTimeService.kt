package com.example.mypasture.quiet_time.application

import com.example.mypasture.attendance.application.AttendanceService
import com.example.mypasture.quiet_time.domain.QuietTimeGroupSize
import com.example.mypasture.quiet_time.domain.QuietTimePassageEntity
import com.example.mypasture.quiet_time.domain.QuietTimeParticipantEntity
import com.example.mypasture.quiet_time.domain.QuietTimePastureEntity
import com.example.mypasture.quiet_time.domain.QuietTimeQrInvitationEntity
import com.example.mypasture.quiet_time.domain.QuietTimeQrStatus
import com.example.mypasture.quiet_time.domain.QuietTimeSessionStatus
import com.example.mypasture.quiet_time.domain.QuietTimeSensitivityLevel
import com.example.mypasture.quiet_time.domain.QuietTimeSessionEntity
import com.example.mypasture.quiet_time.infrastructure.QuietTimePassageRepository
import com.example.mypasture.quiet_time.infrastructure.QuietTimePastureRepository
import com.example.mypasture.quiet_time.infrastructure.QuietTimeParticipantRepository
import com.example.mypasture.quiet_time.infrastructure.QuietTimeQrInvitationRepository
import com.example.mypasture.quiet_time.infrastructure.QuietTimeSessionRepository
import com.example.mypasture.config.QuietTimeQrProperties
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.random.Random
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 큐티 본문 추천, 큐티목장 생성, 오프라인 QR 세션 생성을 처리하는 application service입니다. */
@Service
class QuietTimeService(
    private val passageRepository: QuietTimePassageRepository,
    private val pastureRepository: QuietTimePastureRepository,
    private val sessionRepository: QuietTimeSessionRepository,
    private val qrInvitationRepository: QuietTimeQrInvitationRepository,
    private val participantRepository: QuietTimeParticipantRepository,
    private val attendanceService: AttendanceService,
    private val qrProperties: QuietTimeQrProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun recommendPassages(
        theme: String?,
        beginnerOnly: Boolean,
        size: Int,
    ): List<QuietTimePassageEntity> {
        require(size in 1..20) { "Size must be between 1 and 20." }
        ensureDefaultPassages()
        val normalizedTheme = theme?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        return passageRepository.findByActiveTrue()
            .asSequence()
            .filter { passage -> normalizedTheme == null || passage.normalizedTags().any { it.contains(normalizedTheme) || normalizedTheme.contains(it) } }
            .filter { passage -> !beginnerOnly || passage.beginnerScore >= 4 && passage.sensitivityLevel != QuietTimeSensitivityLevel.HIGH }
            .sortedWith(compareByDescending<QuietTimePassageEntity> { it.totalRecommendationScore() }.thenBy { it.estimatedMinutes })
            .take(size)
            .toList()
    }

    @Transactional
    fun createPasture(
        leaderUserId: UUID,
        name: String,
        leaderName: String,
    ): QuietTimePastureEntity {
        require(name.trim().length in 2..80) { "Pasture name length must be between 2 and 80." }
        require(leaderName.trim().length in 2..80) { "Leader name length must be between 2 and 80." }
        return pastureRepository.save(
            QuietTimePastureEntity(
                name = name.trim(),
                leaderName = leaderName.trim(),
                leaderUserId = leaderUserId,
                inviteCode = generateInviteCode(),
            ),
        )
    }

    @Transactional
    fun createOfflineSession(
        leaderUserId: UUID,
        pastureId: UUID,
        title: String?,
        passageId: UUID?,
        theme: String?,
        meetingDate: LocalDate?,
        meetingStartsAt: Instant?,
        meetingEndsAt: Instant?,
        joinStartsAt: Instant?,
    ): QuietTimeSessionQrView {
        val now = Instant.now(clock)
        val pasture = pastureRepository.findById(pastureId)
            .orElseThrow { QuietTimeNotFoundException("Quiet time pasture was not found: $pastureId") }
        requirePastureLeader(pasture, leaderUserId)

        val passage = passageId?.let {
            passageRepository.findById(it)
                .orElseThrow { QuietTimeNotFoundException("Quiet time passage was not found: $it") }
        } ?: recommendPassages(theme = theme, beginnerOnly = true, size = 1).firstOrNull()
            ?: throw QuietTimeNotFoundException("Recommended quiet time passage was not found.")

        val startsAt = meetingStartsAt ?: now
        val endsAt = meetingEndsAt ?: startsAt.plusSeconds(qrProperties.defaultMeetingDurationMinutes * 60)
        val participationStartsAt = joinStartsAt ?: now
        val expiresAt = endsAt.plusSeconds(qrProperties.expiryGraceMinutes * 60)
        validateQrWindow(now, startsAt, endsAt, participationStartsAt, expiresAt)

        val session = sessionRepository.save(
            QuietTimeSessionEntity(
                pasture = pasture,
                passage = passage,
                title = title?.trim()?.takeIf { it.isNotBlank() } ?: "${pasture.name} 오프라인 큐티",
                meetingDate = meetingDate ?: LocalDate.ofInstant(startsAt, ZoneOffset.UTC),
                meetingStartsAt = startsAt,
                meetingEndsAt = endsAt,
            ),
        )
        val invitation = createInvitation(session, participationStartsAt, expiresAt, now)
        return QuietTimeSessionQrView(session, invitation, invitation.statusAt(now))
    }

    /** 만료·폐기된 코드도 상태 안내를 위해 조회할 수 있지만 참여는 별도로 차단합니다. */
    @Transactional(readOnly = true)
    fun getOfflineSession(offlineCode: String): QuietTimeSessionQrView {
        val invitation = findInvitation(offlineCode)
        return QuietTimeSessionQrView(invitation.session, invitation, invitation.statusAt(Instant.now(clock)))
    }

    @Transactional
    fun joinOfflineSession(
        offlineCode: String,
        displayName: String,
        deviceId: String,
        userId: UUID? = null,
    ): QuietTimeJoinResult {
        require(displayName.trim().length in 1..40) { "Display name length must be between 1 and 40." }
        require(deviceId.trim().length in 8..120) { "Device id length must be between 8 and 120." }

        // QR 행 잠금 안에서 상태와 중복 참여를 함께 확인해 만료·폐기 요청과의 경쟁을 방지합니다.
        val invitation = qrInvitationRepository.findByOfflineCodeForUpdate(normalizeOfflineCode(offlineCode))
            ?: throw QuietTimeNotFoundException("Quiet time QR invitation was not found: $offlineCode")
        val now = Instant.now(clock)
        val qrStatus = invitation.statusAt(now)
        if (qrStatus != QuietTimeQrStatus.OPEN) throwQrStatus(qrStatus)

        val session = invitation.session
        val normalizedDeviceId = deviceId.trim()
        val existing = userId?.let { participantRepository.findBySessionIdAndUserId(session.id, it) }
            ?: participantRepository.findBySessionIdAndDeviceId(session.id, normalizedDeviceId)
        val participant = existing ?: participantRepository.save(
            QuietTimeParticipantEntity(
                session = session,
                displayName = displayName.trim(),
                deviceId = normalizedDeviceId,
                userId = userId,
                joinedAt = now,
            ),
        )
        if (participant.userId == null && userId != null) participant.userId = userId
        if (userId != null) attendanceService.recordQuietTime(userId, session.pasture.id, session.id)

        return QuietTimeJoinResult(
            sessionView = QuietTimeSessionQrView(session, invitation, qrStatus),
            participant = participant,
            joinedAlready = existing != null,
            participantCount = participantRepository.countBySessionId(session.id),
        )
    }

    /** 새 QR을 발급하면서 같은 세션의 기존 활성 QR을 즉시 폐기합니다. */
    @Transactional
    fun reissueQr(
        leaderUserId: UUID,
        sessionId: UUID,
        joinStartsAt: Instant?,
    ): QuietTimeSessionQrView {
        val now = Instant.now(clock)
        val session = sessionRepository.findByIdForUpdate(sessionId)
            ?: throw QuietTimeNotFoundException("Quiet time session was not found: $sessionId")
        requirePastureLeader(session.pasture, leaderUserId)
        if (session.status == QuietTimeSessionStatus.CLOSED) throw QuietTimeSessionClosedException()
        val expiresAt = session.meetingEndsAt.plusSeconds(qrProperties.expiryGraceMinutes * 60)
        if (!expiresAt.isAfter(now)) throw QuietTimeQrExpiredException()

        qrInvitationRepository.findActiveBySessionId(session.id, now).forEach { it.revoke(now) }
        val participationStartsAt = joinStartsAt ?: now
        require(participationStartsAt.isBefore(expiresAt)) { "Join start must be before QR expiry." }
        val invitation = createInvitation(session, participationStartsAt, expiresAt, now)
        return QuietTimeSessionQrView(session, invitation, invitation.statusAt(now))
    }

    /** 리더가 세션을 종료하지 않고 현재 QR만 즉시 폐기합니다. */
    @Transactional
    fun revokeQr(
        leaderUserId: UUID,
        sessionId: UUID,
    ) {
        val now = Instant.now(clock)
        val session = sessionRepository.findByIdForUpdate(sessionId)
            ?: throw QuietTimeNotFoundException("Quiet time session was not found: $sessionId")
        requirePastureLeader(session.pasture, leaderUserId)
        qrInvitationRepository.findActiveBySessionId(session.id, now).forEach { it.revoke(now) }
    }

    /** 세션을 종료하고 아직 유효한 모든 QR을 동시에 폐기합니다. */
    @Transactional
    fun closeSession(
        leaderUserId: UUID,
        sessionId: UUID,
    ) {
        val now = Instant.now(clock)
        val session = sessionRepository.findByIdForUpdate(sessionId)
            ?: throw QuietTimeNotFoundException("Quiet time session was not found: $sessionId")
        requirePastureLeader(session.pasture, leaderUserId)
        session.close()
        qrInvitationRepository.findActiveBySessionId(session.id, now).forEach { it.revoke(now) }
    }

    private fun createInvitation(
        session: QuietTimeSessionEntity,
        joinStartsAt: Instant,
        expiresAt: Instant,
        now: Instant,
    ): QuietTimeQrInvitationEntity {
        val offlineCode = generateOfflineCode()
        val inviteUrl = "${qrProperties.inviteBaseUrl.trimEnd('/')}/$offlineCode"
        return qrInvitationRepository.save(
            QuietTimeQrInvitationEntity(
                session = session,
                offlineCode = offlineCode,
                inviteUrl = inviteUrl,
                joinStartsAt = joinStartsAt,
                expiresAt = expiresAt,
                createdAt = now,
            ),
        )
    }

    private fun validateQrWindow(
        now: Instant,
        meetingStartsAt: Instant,
        meetingEndsAt: Instant,
        joinStartsAt: Instant,
        expiresAt: Instant,
    ) {
        require(meetingEndsAt.isAfter(meetingStartsAt)) { "Meeting end must be after meeting start." }
        require(expiresAt.isAfter(now)) { "QR expiry must be in the future." }
        require(joinStartsAt.isBefore(expiresAt)) { "Join start must be before QR expiry." }
    }

    private fun requirePastureLeader(
        pasture: QuietTimePastureEntity,
        userId: UUID,
    ) {
        if (pasture.leaderUserId != userId) throw QuietTimeForbiddenException()
    }

    private fun findInvitation(offlineCode: String): QuietTimeQrInvitationEntity =
        qrInvitationRepository.findByOfflineCode(normalizeOfflineCode(offlineCode))
            ?: throw QuietTimeNotFoundException("Quiet time QR invitation was not found: $offlineCode")

    private fun normalizeOfflineCode(offlineCode: String): String = offlineCode.trim().uppercase()

    private fun throwQrStatus(status: QuietTimeQrStatus): Nothing =
        when (status) {
            QuietTimeQrStatus.SCHEDULED -> throw QuietTimeQrNotOpenException()
            QuietTimeQrStatus.EXPIRED -> throw QuietTimeQrExpiredException()
            QuietTimeQrStatus.REVOKED -> throw QuietTimeQrRevokedException()
            QuietTimeQrStatus.CLOSED -> throw QuietTimeSessionClosedException()
            QuietTimeQrStatus.OPEN -> error("Open QR must not be rejected")
        }

    private fun ensureDefaultPassages() {
        if (passageRepository.countByActiveTrue() > 0) return
        passageRepository.saveAll(defaultPassages())
    }

    private fun generateInviteCode(): String {
        while (true) {
            val code = randomCode(8)
            if (!pastureRepository.existsByInviteCode(code)) return code
        }
    }

    private fun generateOfflineCode(): String {
        while (true) {
            val code = randomCode(10)
            if (!qrInvitationRepository.existsByOfflineCode(code)) return code
        }
    }

    private fun randomCode(length: Int): String =
        (1..length).map { CODE_CHARS[Random.nextInt(CODE_CHARS.length)] }.joinToString("")

    private fun QuietTimePassageEntity.normalizedTags(): List<String> =
        themeTags.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }

    private fun QuietTimePassageEntity.totalRecommendationScore(): Int =
        readabilityScore + contextScore + sharingScore + applicationScore + beginnerScore

    private fun defaultPassages(): List<QuietTimePassageEntity> =
        listOf(
            passage("불안한 마음을 하나님께 맡기기", "PHP", "빌립보서", 4, 4, 4, 9, "불안,염려,기도,평안,anxiety", 5, 5, 5, 5, 5, QuietTimeSensitivityLevel.LOW, 15, "본문에서 반복되는 명령은 무엇인가?|평강은 어디에서 오는가?", "내가 지금 붙잡고 있는 염려는 무엇인가?|감사로 바꿔 고백할 수 있는 것은 무엇인가?", "목장원들과 나누고 싶은 한 가지 염려는 무엇인가?|이번 주 서로를 위해 어떻게 기도할까?", "오늘 염려 한 가지를 기도 제목으로 적고 목장에 공유하세요.", "하나님, 제 마음과 생각을 주님의 평강으로 지켜 주세요."),
            passage("목자 되신 하나님을 따라가기", "PSA", "시편", 23, 1, 23, 6, "위로,회복,인도,쉼,comfort", 5, 5, 5, 4, 5, QuietTimeSensitivityLevel.LOW, 12, "하나님은 어떤 목자로 묘사되는가?|시인은 어떤 상황에서도 하나님을 신뢰하는가?", "내게 필요한 쉼과 인도는 무엇인가?|요즘 내 영혼을 소생시키는 것은 무엇인가?", "목장 안에서 서로에게 쉼이 되는 방법은 무엇인가?|함께 감사할 하나님의 인도는 무엇인가?", "오늘 나를 인도하신 일을 하나 기록하세요.", "선한 목자 되신 하나님, 오늘 제 걸음을 인도해 주세요."),
            passage("서로 사랑하는 공동체", "ACT", "사도행전", 2, 42, 2, 47, "공동체,목장,나눔,예배,community", 4, 5, 5, 5, 4, QuietTimeSensitivityLevel.LOW, 20, "초대교회가 힘쓴 일은 무엇인가?|공동체 안에서 어떤 열매가 나타나는가?", "우리 목장이 회복해야 할 리듬은 무엇인가?|나는 어떤 나눔에 더 초대받고 있는가?", "이번 모임에서 함께 실천할 공동체 습관은 무엇인가?|새 가족을 어떻게 환대할 수 있을까?", "이번 주 한 명에게 안부와 기도 문자를 보내세요.", "주님, 우리 목장이 말씀과 기도와 나눔 안에 자라게 해 주세요."),
            passage("용서와 새 마음", "EPH", "에베소서", 4, 25, 4, 32, "관계,용서,말,회복,forgiveness", 4, 5, 5, 5, 4, QuietTimeSensitivityLevel.MEDIUM, 18, "버려야 할 옛 모습은 무엇인가?|새 사람의 말과 행동은 무엇인가?", "내 말이 누군가를 세우고 있는가?|용서가 필요한 관계는 어디인가?", "목장 안에서 안전하게 말하기 위해 필요한 약속은 무엇인가?|서로를 세우는 말은 무엇일까?", "오늘 한 사람에게 세우는 말을 건네세요.", "하나님, 제 말과 마음을 새롭게 하셔서 사랑으로 반응하게 해 주세요."),
        )

    private fun passage(
        title: String,
        bookCode: String,
        bookName: String,
        startChapter: Int,
        startVerse: Int,
        endChapter: Int,
        endVerse: Int,
        themeTags: String,
        readabilityScore: Int,
        contextScore: Int,
        sharingScore: Int,
        applicationScore: Int,
        beginnerScore: Int,
        sensitivityLevel: QuietTimeSensitivityLevel,
        estimatedMinutes: Int,
        observationQuestions: String,
        meditationQuestions: String,
        sharingQuestions: String,
        actionGuide: String,
        prayerGuide: String,
    ): QuietTimePassageEntity =
        QuietTimePassageEntity(
            title = title,
            bibleVersion = "KOR1910",
            bookCode = bookCode,
            bookName = bookName,
            startChapter = startChapter,
            startVerse = startVerse,
            endChapter = endChapter,
            endVerse = endVerse,
            themeTags = themeTags,
            readabilityScore = readabilityScore,
            contextScore = contextScore,
            sharingScore = sharingScore,
            applicationScore = applicationScore,
            beginnerScore = beginnerScore,
            sensitivityLevel = sensitivityLevel,
            recommendedGroupSize = QuietTimeGroupSize.PASTURE,
            estimatedMinutes = estimatedMinutes,
            observationQuestions = observationQuestions,
            meditationQuestions = meditationQuestions,
            sharingQuestions = sharingQuestions,
            actionGuide = actionGuide,
            prayerGuide = prayerGuide,
        )

    companion object {
        private const val CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    }
}

/** 큐티목장 리소스를 찾을 수 없을 때 발생하는 예외입니다. */
class QuietTimeNotFoundException(message: String) : RuntimeException(message)

/** 큐티목장 리더가 아닌 사용자가 QR 관리 기능을 요청했을 때 발생하는 예외입니다. */
class QuietTimeForbiddenException : RuntimeException("Only the pasture leader can manage this QR")

/** QR 참여 시작 시간 이전에 참여를 요청했을 때 발생하는 예외입니다. */
class QuietTimeQrNotOpenException : RuntimeException("Quiet time QR is not open yet")

/** QR 유효기간이 종료된 뒤 참여하거나 이미지를 요청했을 때 발생하는 예외입니다. */
class QuietTimeQrExpiredException : RuntimeException("Quiet time QR is expired")

/** 리더가 폐기했거나 재발급으로 교체된 QR을 사용할 때 발생하는 예외입니다. */
class QuietTimeQrRevokedException : RuntimeException("Quiet time QR is revoked")

/** 종료된 큐티 세션에 참여하거나 QR을 재발급하려 할 때 발생하는 예외입니다. */
class QuietTimeSessionClosedException : RuntimeException("Quiet time session is closed")

/** 세션과 특정 QR 발급 이력, 현재 상태를 함께 전달하는 application 조회 모델입니다. */
data class QuietTimeSessionQrView(
    val session: QuietTimeSessionEntity,
    val invitation: QuietTimeQrInvitationEntity,
    val qrStatus: QuietTimeQrStatus,
)

/** QR 스캔 참여 처리 결과와 현재 참여 인원을 담는 application DTO입니다. */
data class QuietTimeJoinResult(
    val sessionView: QuietTimeSessionQrView,
    val participant: QuietTimeParticipantEntity,
    val joinedAlready: Boolean,
    val participantCount: Long,
)
