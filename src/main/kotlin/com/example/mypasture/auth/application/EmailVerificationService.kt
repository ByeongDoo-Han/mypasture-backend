package com.example.mypasture.auth.application

import com.example.mypasture.auth.domain.AuthProvider
import com.example.mypasture.auth.domain.EmailSignupVerificationEntity
import com.example.mypasture.auth.infrastructure.AuthAccountRepository
import com.example.mypasture.auth.infrastructure.EmailSignupVerificationRepository
import com.example.mypasture.auth.infrastructure.UserRepository
import com.example.mypasture.auth.presentation.dto.EmailVerificationConfirmRequest
import com.example.mypasture.auth.presentation.dto.EmailVerificationConfirmedResponse
import com.example.mypasture.auth.presentation.dto.EmailVerificationRequest
import com.example.mypasture.auth.presentation.dto.EmailVerificationRequestedResponse
import com.example.mypasture.config.EmailVerificationProperties
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** 이메일 인증번호 발급, 확인과 일회성 회원가입 증명 소비를 처리합니다. */
@Service
class EmailVerificationService(
    private val coordinator: EmailVerificationCoordinator,
    private val userRepository: UserRepository,
    private val authAccountRepository: AuthAccountRepository,
    private val properties: EmailVerificationProperties,
    private val eventPublisher: ApplicationEventPublisher,
) {
    private val secureRandom = SecureRandom()

    fun request(request: EmailVerificationRequest): EmailVerificationRequestedResponse {
        val email = normalizeEmail(request.email)
        val response = requestedResponse()
        if (userRepository.existsByEmailAndDeletedAtIsNull(email) ||
            authAccountRepository.existsByProviderAndProviderUserId(AuthProvider.EMAIL, email)
        ) {
            return response
        }

        val code = "%06d".format(secureRandom.nextInt(1_000_000))
        val now = Instant.now()
        val expiresAt = now.plusSeconds(properties.codeTtlMinutes * 60)
        val issued = tryIssue(email, hashCode(email, code), expiresAt, now)
        if (issued) eventPublisher.publishEvent(EmailVerificationRequestedEvent(email, code, expiresAt))
        return response
    }

    fun confirm(request: EmailVerificationConfirmRequest): EmailVerificationConfirmedResponse {
        val email = normalizeEmail(request.email)
        val rawToken = generateSignupToken()
        val now = Instant.now()
        val tokenExpiresAt = now.plusSeconds(properties.signupTokenTtlMinutes * 60)
        coordinator.confirm(
            email = email,
            submittedCodeHash = hashCode(email, request.code),
            verificationTokenHash = hashToken(rawToken),
            tokenExpiresAt = tokenExpiresAt,
            now = now,
            maxAttempts = properties.maxAttempts,
        )
        return EmailVerificationConfirmedResponse(rawToken, tokenExpiresAt)
    }

    /** 회원과 인증 계정이 생성되는 트랜잭션 안에서 이메일에 묶인 증명을 한 번만 소비합니다. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun consumeForSignup(email: String, rawToken: String?) {
        if (!properties.required) return
        val token = rawToken?.trim()?.takeIf { it.isNotEmpty() } ?: throw EmailVerificationRequiredException()
        val verification = coordinator.findTokenForUpdate(hashToken(token)) ?: throw EmailVerificationRequiredException()
        val now = Instant.now()
        if (verification.email != normalizeEmail(email) || !verification.canConsumeAt(now)) {
            throw EmailVerificationRequiredException()
        }
        verification.consume(now)
    }

    private fun tryIssue(email: String, codeHash: String, expiresAt: Instant, now: Instant): Boolean =
        try {
            coordinator.issue(email, codeHash, expiresAt, now, properties.requestCooldownSeconds)
        } catch (_: DataIntegrityViolationException) {
            // 서로 다른 서버가 같은 이메일의 첫 요청을 동시에 생성하면 unique 승자 커밋 후 한 번 재조회합니다.
            coordinator.issue(email, codeHash, expiresAt, now, properties.requestCooldownSeconds)
        }

    private fun requestedResponse() =
        EmailVerificationRequestedResponse(
            expiresInSeconds = properties.codeTtlMinutes * 60,
            resendAfterSeconds = properties.requestCooldownSeconds,
        )

    private fun hashCode(email: String, code: String): String {
        val secret = properties.hmacSecret.toByteArray(StandardCharsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }
        return mac.doFinal("$email:${code.trim()}".toByteArray(StandardCharsets.UTF_8)).toHex()
    }

    private fun hashToken(token: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(StandardCharsets.UTF_8))
            .toHex()

    private fun generateSignupToken(): String =
        ByteArray(SIGNUP_TOKEN_BYTES)
            .also(secureRandom::nextBytes)
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    private fun normalizeEmail(email: String): String = email.trim().lowercase()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        private const val SIGNUP_TOKEN_BYTES = 32
    }
}

/** 이메일 인증 상태 변경을 짧은 DB 트랜잭션과 행 잠금으로 직렬화합니다. */
@Service
class EmailVerificationCoordinator(
    private val repository: EmailSignupVerificationRepository,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun issue(
        email: String,
        codeHash: String,
        expiresAt: Instant,
        now: Instant,
        cooldownSeconds: Long,
    ): Boolean {
        val current = repository.findByEmailForUpdate(email)
        if (current != null && current.sentAt.plusSeconds(cooldownSeconds).isAfter(now)) return false
        if (current == null) {
            repository.saveAndFlush(
                EmailSignupVerificationEntity(
                    email = email,
                    codeHash = codeHash,
                    expiresAt = expiresAt,
                    sentAt = now,
                ),
            )
        } else {
            current.refresh(codeHash, expiresAt, now)
        }
        return true
    }

    @Transactional(noRollbackFor = [InvalidEmailVerificationCodeException::class])
    fun confirm(
        email: String,
        submittedCodeHash: String,
        verificationTokenHash: String,
        tokenExpiresAt: Instant,
        now: Instant,
        maxAttempts: Int,
    ) {
        val verification = repository.findByEmailForUpdate(email) ?: throw InvalidEmailVerificationCodeException()
        if (!verification.canAttemptAt(now, maxAttempts)) throw InvalidEmailVerificationCodeException()
        if (!MessageDigest.isEqual(verification.codeHash.toByteArray(), submittedCodeHash.toByteArray())) {
            verification.recordFailure(now)
            throw InvalidEmailVerificationCodeException()
        }
        verification.markVerified(verificationTokenHash, tokenExpiresAt, now)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun findTokenForUpdate(tokenHash: String): EmailSignupVerificationEntity? =
        repository.findByVerificationTokenHashForUpdate(tokenHash)
}

/** 인증번호가 만료되었거나 불일치하거나 허용 시도 횟수를 초과했습니다. */
class InvalidEmailVerificationCodeException : RuntimeException("Email verification code is invalid or expired")

/** 이메일 소유 확인 없이 이메일/비밀번호 회원가입을 시도했습니다. */
class EmailVerificationRequiredException : RuntimeException("Verified email signup token is required")
