package com.example.mypasture.quiet_time.application

import com.example.mypasture.quiet_time.domain.QuietTimeReflectionEntity
import com.example.mypasture.quiet_time.infrastructure.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/** QT 참여 권한을 확인하고 개인 묵상 저장과 목장 공유 목록을 처리합니다. */
@Service
class QuietTimeReflectionService(private val sessionRepository: QuietTimeSessionRepository, private val participantRepository: QuietTimeParticipantRepository, private val reflectionRepository: QuietTimeReflectionRepository) {
    @Transactional
    fun save(sessionId: UUID, userId: UUID, content: String, shared: Boolean): QuietTimeReflectionEntity {
        require(content.trim().length in 1..3000) { "Reflection length must be between 1 and 3000." }
        val session = sessionRepository.findById(sessionId).orElseThrow { QuietTimeNotFoundException("Quiet time session was not found: $sessionId") }
        requireParticipantOrLeader(session, userId)
        val now = Instant.now()
        val reflection = reflectionRepository.findBySessionIdAndUserId(sessionId, userId) ?: QuietTimeReflectionEntity(session = session, userId = userId, content = content.trim(), sharedWithPasture = shared, createdAt = now, updatedAt = now)
        reflection.update(content.trim(), shared, now)
        return reflectionRepository.save(reflection)
    }
    @Transactional(readOnly = true) fun mine(sessionId: UUID, userId: UUID) = reflectionRepository.findBySessionIdAndUserId(sessionId, userId)
    @Transactional(readOnly = true)
    fun shared(sessionId: UUID, userId: UUID): List<QuietTimeReflectionEntity> {
        val session = sessionRepository.findById(sessionId).orElseThrow { QuietTimeNotFoundException("Quiet time session was not found: $sessionId") }
        requireParticipantOrLeader(session, userId)
        return reflectionRepository.findAllBySessionIdAndSharedWithPastureTrueOrderByUpdatedAtDesc(sessionId)
    }
    private fun requireParticipantOrLeader(session: com.example.mypasture.quiet_time.domain.QuietTimeSessionEntity, userId: UUID) {
        if (!session.isManagedBy(userId) && !participantRepository.existsBySessionIdAndUserId(session.id, userId)) throw QuietTimeForbiddenException()
    }
}
