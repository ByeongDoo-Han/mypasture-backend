package com.example.mypasture.quiet_time.application

import com.example.mypasture.quiet_time.domain.QuietTimeQuestionAnswerEntity
import com.example.mypasture.quiet_time.domain.QuietTimeQuestionCategory
import com.example.mypasture.quiet_time.domain.QuietTimeSessionEntity
import com.example.mypasture.quiet_time.domain.QuietTimeSessionStatus
import com.example.mypasture.quiet_time.infrastructure.QuietTimeParticipantRepository
import com.example.mypasture.quiet_time.infrastructure.QuietTimeQuestionAnswerRepository
import com.example.mypasture.quiet_time.infrastructure.QuietTimeSessionRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/** QT 참여 권한과 질문 위치를 검증하고 질문별 최신 답변을 게시·조회합니다. */
@Service
class QuietTimeQuestionAnswerService(
    private val sessionRepository: QuietTimeSessionRepository,
    private val participantRepository: QuietTimeParticipantRepository,
    private val answerRepository: QuietTimeQuestionAnswerRepository,
    private val eventPublisher: ApplicationEventPublisher,
) {
    @Transactional
    fun save(
        sessionId: UUID,
        userId: UUID,
        category: QuietTimeQuestionCategory,
        questionIndex: Int,
        content: String,
    ): QuietTimeQuestionAnswerView {
        val normalizedContent = content.trim()
        require(normalizedContent.length in 1..MAX_ANSWER_LENGTH) { "답변은 1자 이상 ${MAX_ANSWER_LENGTH}자 이하여야 합니다." }
        // 같은 모임에서 최초 답변 생성과 수정 판정을 직렬화해 유일 키 경쟁을 방지합니다.
        val session = sessionRepository.findByIdForUpdate(sessionId)
            ?: throw QuietTimeNotFoundException("Quiet time session was not found: $sessionId")
        if (session.status == QuietTimeSessionStatus.CLOSED) throw QuietTimeSessionClosedException()
        val authorDisplayName = requireParticipantOrLeader(session, userId)
        val questionText = resolveQuestion(session, category, questionIndex)
        val now = Instant.now()
        val answer = answerRepository.findBySessionIdAndUserIdAndQuestionCategoryAndQuestionIndex(
            sessionId,
            userId,
            category,
            questionIndex,
        ) ?: QuietTimeQuestionAnswerEntity(
            session = session,
            userId = userId,
            authorDisplayName = authorDisplayName,
            questionCategory = category,
            questionIndex = questionIndex,
            questionText = questionText,
            content = normalizedContent,
            createdAt = now,
            updatedAt = now,
        )
        answer.update(authorDisplayName, normalizedContent, now)
        val view = answerRepository.saveAndFlush(answer).toView()
        eventPublisher.publishEvent(QuietTimeAnswerChangedEvent(view))
        return view
    }

    @Transactional(readOnly = true)
    fun list(sessionId: UUID, userId: UUID): List<QuietTimeQuestionAnswerView> {
        val session = findSession(sessionId)
        requireParticipantOrLeader(session, userId)
        return answerRepository.findAllBySessionIdOrderByQuestionCategoryAscQuestionIndexAscUpdatedAtAsc(sessionId)
            .map(QuietTimeQuestionAnswerEntity::toView)
    }

    @Transactional(readOnly = true)
    fun requireReadAccess(sessionId: UUID, userId: UUID) {
        val session = findSession(sessionId)
        requireParticipantOrLeader(session, userId)
        if (session.status == QuietTimeSessionStatus.CLOSED) throw QuietTimeSessionClosedException()
    }

    private fun findSession(sessionId: UUID): QuietTimeSessionEntity =
        sessionRepository.findById(sessionId)
            .orElseThrow { QuietTimeNotFoundException("Quiet time session was not found: $sessionId") }

    private fun requireParticipantOrLeader(session: QuietTimeSessionEntity, userId: UUID): String {
        if (session.isManagedBy(userId)) return session.managerDisplayName()
        return participantRepository.findBySessionIdAndUserId(session.id, userId)?.displayName
            ?: throw QuietTimeParticipationRequiredException()
    }

    private fun resolveQuestion(
        session: QuietTimeSessionEntity,
        category: QuietTimeQuestionCategory,
        questionIndex: Int,
    ): String {
        val encoded = when (category) {
            QuietTimeQuestionCategory.OBSERVATION -> session.passage.observationQuestions
            QuietTimeQuestionCategory.MEDITATION -> session.passage.meditationQuestions
            QuietTimeQuestionCategory.SHARING -> session.passage.sharingQuestions
        }
        val questions = encoded.split("|").map(String::trim).filter(String::isNotBlank)
        require(questionIndex in questions.indices) { "존재하지 않는 QT 질문입니다." }
        return questions[questionIndex]
    }

    companion object {
        private const val MAX_ANSWER_LENGTH = 2_000
    }
}

/** API 및 실시간 이벤트에서 공유하는 질문별 답변의 불변 projection입니다. */
data class QuietTimeQuestionAnswerView(
    val id: UUID,
    val sessionId: UUID,
    val userId: UUID,
    val authorDisplayName: String,
    val questionCategory: QuietTimeQuestionCategory,
    val questionIndex: Int,
    val questionText: String,
    val content: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** 커밋 후 동일 QT 모임 구독자에게 전달할 답변 변경 이벤트입니다. */
data class QuietTimeAnswerChangedEvent(val answer: QuietTimeQuestionAnswerView)

fun QuietTimeQuestionAnswerEntity.toView() =
    QuietTimeQuestionAnswerView(
        id = id,
        sessionId = session.id,
        userId = userId,
        authorDisplayName = authorDisplayName,
        questionCategory = questionCategory,
        questionIndex = questionIndex,
        questionText = questionText,
        content = content,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

/** QT 모임에 참여하지 않은 사용자가 답변을 조회하거나 작성했습니다. */
class QuietTimeParticipationRequiredException : RuntimeException("Quiet time participation is required")
