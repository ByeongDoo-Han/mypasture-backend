package com.example.mypasture.auth.application

import com.example.mypasture.auth.domain.AdminEmailLoginStateEntity
import com.example.mypasture.auth.infrastructure.AdminEmailLoginStateRepository
import com.example.mypasture.auth.infrastructure.UserRepository
import com.example.mypasture.auth.presentation.dto.AdminEmailLoginConfirmRequest
import com.example.mypasture.auth.presentation.dto.AdminEmailLoginRequest
import com.example.mypasture.auth.presentation.dto.AdminEmailLoginRequestedResponse
import com.example.mypasture.auth.presentation.dto.UserLoginResponse
import com.example.mypasture.config.AdminEmailLoginProperties
import com.example.mypasture.user.domain.UserRole
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Locale
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** 관리자 계정을 잠가 발송 제한과 일회성 인증·세션 발급을 원자적으로 처리합니다. */
@Service
class AdminEmailLoginService(
    private val users: UserRepository,
    private val states: AdminEmailLoginStateRepository,
    private val sender: AdminEmailLoginSender,
    private val properties: AdminEmailLoginProperties,
    private val authService: AuthService,
    private val entityManager: EntityManager,
) {
    private val random = SecureRandom()

    @Transactional(timeout = 20)
    fun request(request: AdminEmailLoginRequest): AdminEmailLoginRequestedResponse {
        val id = UUID.randomUUID()
        val response = AdminEmailLoginRequestedResponse(id)
        if (!properties.enabled) return response
        val user = users.findByEmailForUpdate(request.email.trim().lowercase(Locale.ROOT))
            ?.takeIf { it.role == UserRole.ADMIN } ?: return response
        val now = Instant.now()
        val state = states.findById(user.id).orElseGet { states.save(AdminEmailLoginStateEntity(user.id)) }
        state.refreshWindows(now)
        if (!state.canSendAt(now)) return response
        val code = random.nextInt(1_000_000).toString().padStart(6, '0')
        state.beginChallenge(id, hash(id, user.id, code), user.authVersion, now)
        try {
            sender.send(requireNotNull(user.email), code, requireNotNull(state.expiresAt))
        } catch (_: AdminEmailDeliveryException) {
            // 실패한 발송도 쿨다운/한도에 포함하며 어떤 코드로도 로그인할 수 없게 합니다.
            state.codeHash = null
        }
        return response
    }

    @Transactional(noRollbackFor = [InvalidAdminEmailCodeException::class])
    fun confirm(request: AdminEmailLoginConfirmRequest): UserLoginResponse {
        if (!properties.enabled) throw InvalidAdminEmailCodeException()
        val found = states.findByChallengeId(request.challengeId) ?: throw InvalidAdminEmailCodeException()
        val user = users.findActiveByIdForUpdate(found.userId) ?: throw InvalidAdminEmailCodeException()
        // 잠금을 기다리는 동안 다른 확인/재발송이 변경한 영속 상태를 다시 읽습니다.
        entityManager.refresh(found)
        val now = Instant.now()
        found.refreshWindows(now)
        val actualHash = hash(request.challengeId, user.id, request.code)
        val validHash = MessageDigest.isEqual(actualHash.toByteArray(), (found.codeHash ?: "").toByteArray())
        if (found.challengeId != request.challengeId || found.failedAttempts >= 5 ||
            found.consumedAt != null || found.expiresAt?.isAfter(now) != true ||
            user.role != UserRole.ADMIN || user.authVersion != found.authVersion || !validHash) {
            found.recordFailure(now)
            throw InvalidAdminEmailCodeException()
        }
        val response = authService.issueAdminEmailSession(user.id, found.authVersion)
        found.consume(now)
        return response
    }

    private fun hash(challengeId: UUID, userId: UUID, code: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(properties.hmacSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal("admin-email-login:$challengeId:$userId:$code".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

/** 만료·횟수 초과·권한 철회·불일치를 동일한 인증 실패로 처리합니다. */
class InvalidAdminEmailCodeException : RuntimeException("Admin email login code is invalid or expired")
