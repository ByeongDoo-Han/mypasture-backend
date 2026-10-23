package com.example.mypasture.quiet_time.application

import com.example.mypasture.auth.infrastructure.UserRepository
import com.example.mypasture.quiet_time.domain.QuietTimePastureEntity
import com.example.mypasture.quiet_time.domain.QuietTimePastureInviteEntity
import com.example.mypasture.quiet_time.domain.QuietTimePastureJoinRequestEntity
import com.example.mypasture.quiet_time.domain.QuietTimePastureJoinStatus
import com.example.mypasture.quiet_time.domain.QuietTimePastureMembershipEntity
import com.example.mypasture.quiet_time.domain.QuietTimePastureRole
import com.example.mypasture.quiet_time.infrastructure.QuietTimePastureInviteRepository
import com.example.mypasture.quiet_time.infrastructure.QuietTimePastureJoinRequestRepository
import com.example.mypasture.quiet_time.infrastructure.QuietTimePastureMembershipRepository
import com.example.mypasture.quiet_time.infrastructure.QuietTimePastureRepository
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class QuietTimePastureState(
    val status: String,
    val pasture: QuietTimePastureEntity?,
    val requestId: UUID? = null,
    val role: QuietTimePastureRole? = null,
)

data class QuietTimePastureInviteView(
    val code: String,
    val inviteUrl: String,
    val expiresAt: Instant,
)

class QuietTimePastureConflictException : RuntimeException()
class QuietTimePastureInviteInvalidException : RuntimeException()
class QuietTimePastureInviteLimitException : RuntimeException()
class QuietTimePastureRequestNotFoundException : RuntimeException()
class QuietTimePastureRequestExpiredException : RuntimeException()
class QuietTimePastureLeaderRequiredException : RuntimeException()

/** 고정 목장 단일 소속, 단기 초대, 임시 본명 가입 승인 규칙을 관리합니다. */
@Service
class QuietTimePastureMembershipService(
    private val userRepository: UserRepository,
    private val pastureRepository: QuietTimePastureRepository,
    private val membershipRepository: QuietTimePastureMembershipRepository,
    private val inviteRepository: QuietTimePastureInviteRepository,
    private val requestRepository: QuietTimePastureJoinRequestRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val random = SecureRandom()
    private val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    @Transactional
    fun createPasture(userId: UUID, name: String, leaderName: String): QuietTimePastureEntity {
        require(name.trim().length in 2..80) { "Pasture name length must be between 2 and 80." }
        require(leaderName.trim().length in 2..80) { "Leader name length must be between 2 and 80." }
        lockUser(userId)
        requireFree(userId)
        val pasture = pastureRepository.save(
            QuietTimePastureEntity(
                name = name.trim(),
                leaderName = leaderName.trim(),
                leaderUserId = userId,
                inviteCode = UUID.randomUUID().toString().replace("-", "").take(16).uppercase(),
            ),
        )
        membershipRepository.saveAndFlush(QuietTimePastureMembershipEntity(userId, pasture, QuietTimePastureRole.LEADER))
        return pasture
    }

    @Transactional
    fun state(userId: UUID): QuietTimePastureState {
        membershipRepository.findByUserId(userId)?.let { return QuietTimePastureState(it.role.name, it.pasture, role = it.role) }
        val pending = currentPending(userId) ?: return QuietTimePastureState("NONE", null)
        return QuietTimePastureState("PENDING", pending.pasture, pending.id)
    }

    @Transactional(readOnly = true)
    fun managedPastures(userId: UUID): List<QuietTimePastureEntity> =
        membershipRepository.findByUserId(userId)?.takeIf { it.role == QuietTimePastureRole.LEADER }?.let { listOf(it.pasture) } ?: emptyList()

    @Transactional(readOnly = true)
    fun requireLeader(userId: UUID, pastureId: UUID) {
        val membership = membershipRepository.findByUserId(userId)
        if (membership?.pasture?.id != pastureId || membership.role != QuietTimePastureRole.LEADER) {
            throw QuietTimePastureLeaderRequiredException()
        }
    }

    @Transactional
    fun issueInvite(userId: UUID, pastureId: UUID): QuietTimePastureInviteView {
        val member = membershipRepository.findByUserId(userId)
        if (member?.pasture?.id != pastureId) throw QuietTimePastureConflictException()
        val now = Instant.now(clock)
        if (inviteRepository.countByCreatedByUserIdAndCreatedAtAfter(userId, now.minusSeconds(60)) >= 5) {
            throw QuietTimePastureInviteLimitException()
        }
        val code = buildString(12) { repeat(12) { append(alphabet[random.nextInt(alphabet.length)]) } }
        val expiresAt = now.plusSeconds(60)
        inviteRepository.save(
            QuietTimePastureInviteEntity(
                pasture = member.pasture,
                createdByUserId = userId,
                codeHash = hash(code),
                expiresAt = expiresAt,
                createdAt = now,
            ),
        )
        return QuietTimePastureInviteView(code, "https://app.mypasture.app/pasture/join/$code", expiresAt)
    }

    @Transactional(readOnly = true)
    fun resolveInvite(code: String): QuietTimePastureEntity = validInvite(code).pasture

    @Transactional
    fun requestJoin(userId: UUID, code: String, realName: String): QuietTimePastureJoinRequestEntity {
        val normalizedName = realName.trim().replace(Regex("\\s+"), " ")
        require(normalizedName.length in 2..80 && normalizedName.none { it.isISOControl() }) {
            "Real name length must be between 2 and 80."
        }
        lockUser(userId)
        requireFree(userId)
        val invite = validInvite(code)
        val now = Instant.now(clock)
        return requestRepository.saveAndFlush(
            QuietTimePastureJoinRequestEntity(
                pasture = invite.pasture,
                userId = userId,
                realName = normalizedName,
                createdAt = now,
                expiresAt = now.plus(Duration.ofDays(7)),
            ),
        )
    }

    @Transactional
    fun pendingRequests(leaderUserId: UUID, pastureId: UUID): List<QuietTimePastureJoinRequestEntity> {
        requireLeader(leaderUserId, pastureId)
        val now = Instant.now(clock)
        return requestRepository.findAllByPastureIdAndStatusOrderByCreatedAtAsc(pastureId, QuietTimePastureJoinStatus.PENDING)
            .filter { request ->
                if (!now.isBefore(request.expiresAt)) {
                    request.decide(QuietTimePastureJoinStatus.EXPIRED, now)
                    false
                } else true
            }
    }

    @Transactional
    fun decide(leaderUserId: UUID, pastureId: UUID, requestId: UUID, approve: Boolean) {
        requireLeader(leaderUserId, pastureId)
        val initial = requestRepository.findById(requestId).orElseThrow { QuietTimePastureRequestNotFoundException() }
        if (initial.pasture.id != pastureId) throw QuietTimePastureRequestNotFoundException()
        lockUser(initial.userId)
        val request = requestRepository.findByIdForUpdate(requestId) ?: throw QuietTimePastureRequestNotFoundException()
        if (request.status != QuietTimePastureJoinStatus.PENDING) throw QuietTimePastureConflictException()
        val now = Instant.now(clock)
        if (!now.isBefore(request.expiresAt)) {
            // 정리 작업이 본명을 지웁니다. 이 트랜잭션을 예외로 롤백하므로 여기서는 변경하지 않습니다.
            throw QuietTimePastureRequestExpiredException()
        }
        if (approve) {
            if (membershipRepository.existsById(request.userId)) throw QuietTimePastureConflictException()
            membershipRepository.saveAndFlush(
                QuietTimePastureMembershipEntity(request.userId, request.pasture, QuietTimePastureRole.MEMBER, now),
            )
        }
        request.decide(if (approve) QuietTimePastureJoinStatus.APPROVED else QuietTimePastureJoinStatus.REJECTED, now)
    }

    /** 만료된 신청의 본명을 일 분 간격으로 제거합니다. */
    @Scheduled(fixedDelay = 60_000)
    @Transactional
    fun expirePendingNames() {
        val now = Instant.now(clock)
        requestRepository.findAllByStatusAndExpiresAtBefore(QuietTimePastureJoinStatus.PENDING, now)
            .forEach { it.decide(QuietTimePastureJoinStatus.EXPIRED, now) }
    }

    private fun currentPending(userId: UUID): QuietTimePastureJoinRequestEntity? {
        val request = requestRepository.findByActiveUserId(userId) ?: return null
        val now = Instant.now(clock)
        if (!now.isBefore(request.expiresAt)) {
            request.decide(QuietTimePastureJoinStatus.EXPIRED, now)
            return null
        }
        return request
    }

    private fun requireFree(userId: UUID) {
        if (membershipRepository.existsById(userId) || currentPending(userId) != null) {
            throw QuietTimePastureConflictException()
        }
    }

    private fun lockUser(userId: UUID) {
        userRepository.findActiveByIdForUpdate(userId) ?: throw QuietTimePastureConflictException()
    }

    private fun validInvite(rawCode: String): QuietTimePastureInviteEntity {
        val code = rawCode.trim().uppercase()
        if (code.length != 12 || code.any { it !in alphabet }) throw QuietTimePastureInviteInvalidException()
        val invite = inviteRepository.findByCodeHash(hash(code)) ?: throw QuietTimePastureInviteInvalidException()
        if (!Instant.now(clock).isBefore(invite.expiresAt)) throw QuietTimePastureInviteInvalidException()
        return invite
    }

    private fun hash(code: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.toByteArray(Charsets.US_ASCII)))
}
