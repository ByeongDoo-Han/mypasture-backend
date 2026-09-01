package com.example.mypasture.auth.application

import com.example.mypasture.auth.domain.AuthAccountEntity
import com.example.mypasture.auth.domain.AuthProvider
import com.example.mypasture.auth.domain.PasswordResetTokenEntity
import com.example.mypasture.auth.domain.RefreshTokenEntity
import com.example.mypasture.auth.domain.SocialLoginChallengeEntity
import com.example.mypasture.auth.infrastructure.AuthAccountRepository
import com.example.mypasture.auth.infrastructure.JwtTokenProvider
import com.example.mypasture.auth.infrastructure.PasswordResetTokenRepository
import com.example.mypasture.auth.infrastructure.RefreshTokenRepository
import com.example.mypasture.auth.infrastructure.SocialLoginChallengeRepository
import com.example.mypasture.auth.infrastructure.UserRepository
import com.example.mypasture.auth.presentation.dto.DeleteAccountRequest
import com.example.mypasture.auth.presentation.dto.PasswordResetConfirmRequest
import com.example.mypasture.auth.presentation.dto.PasswordResetRequest
import com.example.mypasture.auth.presentation.dto.PasswordResetRequestedResponse
import com.example.mypasture.auth.presentation.dto.RefreshAccessTokenRequest
import com.example.mypasture.auth.presentation.dto.SocialLoginRequest
import com.example.mypasture.auth.presentation.dto.SocialLoginChallengeResponse
import com.example.mypasture.auth.presentation.dto.UserDto
import com.example.mypasture.auth.presentation.dto.UserLoginRequest
import com.example.mypasture.auth.presentation.dto.UserLoginResponse
import com.example.mypasture.auth.presentation.dto.UserSignupRequest
import com.example.mypasture.auth.presentation.dto.UserSignupResponse
import com.example.mypasture.auth.presentation.dto.UpdateMyProfileRequest
import com.example.mypasture.config.AuthSecurityProperties
import com.example.mypasture.config.JwtProperties
import com.example.mypasture.config.OAuthProperties
import com.example.mypasture.user.domain.UserEntity
import com.example.mypasture.policy.application.PolicyConsentService
import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.Duration
import java.util.Base64
import java.util.Locale
import java.util.UUID

/** 회원가입, 로그인, 비밀번호 재설정, 회원탈퇴 같은 인증 use case를 처리하는 application service입니다. */
@Service
class AuthService(
    private val userRepository: UserRepository,
    private val authAccountRepository: AuthAccountRepository,
    private val passwordResetTokenRepository: PasswordResetTokenRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val socialLoginChallengeRepository: SocialLoginChallengeRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtTokenProvider: JwtTokenProvider,
    private val socialOAuthClients: List<SocialOAuthClient>,
    private val authSecurityProperties: AuthSecurityProperties,
    private val jwtProperties: JwtProperties,
    private val oauthProperties: OAuthProperties,
    private val eventPublisher: ApplicationEventPublisher,
    private val emailVerificationService: EmailVerificationService,
    private val policyConsentService: PolicyConsentService,
    private val personalDataEraser: AccountPersonalDataEraser,
) {
    private val secureRandom = SecureRandom()

    @Transactional
    fun signup(request: UserSignupRequest): UserSignupResponse {
        policyConsentService.validateSignup(request.policyConsents)
        val email = normalizeEmail(request.email)
        if (userRepository.existsByEmailAndDeletedAtIsNull(email) ||
            authAccountRepository.existsByProviderAndProviderUserId(AuthProvider.EMAIL, email)
        ) {
            throw EmailAlreadyRegisteredException()
        }
        emailVerificationService.consumeForSignup(email, request.verificationToken)

        val user =
            userRepository.save(
                UserEntity(
                    email = email,
                    displayName = request.displayName.trim(),
                ),
            )

        authAccountRepository.save(
            AuthAccountEntity(
                user = user,
                provider = AuthProvider.EMAIL,
                providerUserId = email,
                passwordHash = passwordEncoder.encode(request.password),
            ),
        )

        policyConsentService.recordSignup(user.id, request.policyConsents)

        return UserSignupResponse(UserDto.from(user))
    }

    @Transactional
    fun login(request: UserLoginRequest): UserLoginResponse {
        val email = normalizeEmail(request.email)
        val account =
            authAccountRepository.findByProviderAndProviderUserId(AuthProvider.EMAIL, email)
                ?.takeIf { it.user.deletedAt == null }
                ?: throw InvalidCredentialsException()
        val passwordHash = account.passwordHash ?: throw InvalidCredentialsException()
        if (!passwordEncoder.matches(request.password, passwordHash)) throw InvalidCredentialsException()

        return issueLoginResponse(account.user)
    }

    @Transactional
    fun socialLogin(request: SocialLoginRequest): UserLoginResponse {
        if (request.provider == AuthProvider.EMAIL) throw UnsupportedSocialProviderException()

        val command =
            SocialLoginCommand(
                provider = request.provider,
                idToken = request.idToken?.trim()?.takeIf { it.isNotBlank() },
                accessToken = request.accessToken?.trim()?.takeIf { it.isNotBlank() },
                displayNameHint = request.displayName?.trim()?.takeIf { it.isNotBlank() },
            )
        val client = socialOAuthClients.firstOrNull { it.provider == request.provider } ?: throw UnsupportedSocialProviderException()
        val profile = client.fetchProfile(command)
        if (request.provider == AuthProvider.GOOGLE || request.provider == AuthProvider.APPLE) {
            consumeSocialLoginChallenge(request.challengeId, profile.nonce)
        }

        val existingAccount = authAccountRepository.findByProviderAndProviderUserId(profile.provider, profile.providerUserId)
        if (existingAccount != null && existingAccount.user.deletedAt == null) return issueLoginResponse(existingAccount.user)

        val user = findOrCreateSocialUser(profile)
        authAccountRepository.save(
            AuthAccountEntity(
                user = user,
                provider = profile.provider,
                providerUserId = profile.providerUserId,
            ),
        )

        return issueLoginResponse(user)
    }

    /** 서버에서 생성한 nonce를 저장하고 OIDC 로그인 한 번에만 사용할 challenge를 발급합니다. */
    @Transactional
    fun createSocialLoginChallenge(): SocialLoginChallengeResponse {
        val now = Instant.now()
        val nonce = generateSocialNonce()
        val challenge = socialLoginChallengeRepository.save(
            SocialLoginChallengeEntity(
                nonceHash = hashOpaqueToken(nonce),
                expiresAt = now.plusSeconds(oauthProperties.challengeTtlSeconds),
                createdAt = now,
            ),
        )
        return SocialLoginChallengeResponse(challenge.id, nonce, challenge.expiresAt)
    }

    /** refresh token을 한 번만 소비하고 같은 family의 새 토큰으로 회전합니다. */
    @Transactional(noRollbackFor = [RefreshTokenReuseDetectedException::class])
    fun refresh(request: RefreshAccessTokenRequest): UserLoginResponse {
        val now = Instant.now()
        val current = refreshTokenRepository.findByTokenHashForUpdate(hashOpaqueToken(request.refreshToken.trim()))
            ?: throw InvalidRefreshTokenException()
        if (current.usedAt != null || current.revokedAt != null) {
            refreshTokenRepository.revokeFamily(current.familyId, now)
            current.user.invalidateSessions(now)
            throw RefreshTokenReuseDetectedException()
        }
        if (!current.expiresAt.isAfter(now) || current.user.deletedAt != null) {
            current.revoke(now)
            throw InvalidRefreshTokenException()
        }

        val rawReplacement = generateOpaqueToken()
        val replacementHash = hashOpaqueToken(rawReplacement)
        current.rotate(now, replacementHash)
        refreshTokenRepository.save(
            RefreshTokenEntity(
                user = current.user,
                familyId = current.familyId,
                tokenHash = replacementHash,
                expiresAt = now.plusSeconds(jwtProperties.refreshTokenTtlSeconds),
                createdAt = now,
            ),
        )
        return issueLoginResponse(current.user, rawReplacement)
    }

    /** 로그아웃 시 사용자의 access token 인증 버전과 모든 refresh token을 함께 폐기합니다. */
    @Transactional
    fun logout(userId: UUID) {
        val user = userRepository.findActiveByIdForUpdate(userId) ?: throw InvalidCredentialsException()
        val now = Instant.now()
        refreshTokenRepository.revokeAllByUserId(user.id, now)
        user.invalidateSessions(now)
    }

    /**
     * 계정 존재 여부를 응답으로 노출하지 않으며, 활성 이메일 계정에만 만료되는 일회성 토큰을 발급합니다.
     * 같은 계정의 동시 요청은 사용자 행 잠금으로 직렬화하고 최근 요청은 cooldown 동안 무시합니다.
     */
    @Transactional
    fun requestPasswordReset(request: PasswordResetRequest): PasswordResetRequestedResponse {
        val email = normalizeEmail(request.email)
        val user = userRepository.findByEmailForUpdate(email) ?: return PasswordResetRequestedResponse()
        val emailAccount = authAccountRepository.findByUserIdAndProvider(user.id, AuthProvider.EMAIL)
            ?: return PasswordResetRequestedResponse()
        if (emailAccount.passwordHash == null) return PasswordResetRequestedResponse()

        val now = Instant.now()
        val latestToken = passwordResetTokenRepository.findTopByUserIdOrderByCreatedAtDesc(user.id)
        if (latestToken != null &&
            latestToken.createdAt.plusSeconds(authSecurityProperties.requestCooldownSeconds).isAfter(now)
        ) {
            return PasswordResetRequestedResponse()
        }

        passwordResetTokenRepository.revokeAllActiveByUserId(user.id, now)
        val rawToken = generateResetToken()
        val expiresAt = now.plusSeconds(authSecurityProperties.tokenTtlMinutes * 60)
        passwordResetTokenRepository.save(
            PasswordResetTokenEntity(
                user = user,
                tokenHash = hashResetToken(rawToken),
                expiresAt = expiresAt,
                createdAt = now,
            ),
        )
        eventPublisher.publishEvent(
            PasswordResetRequestedEvent(
                email = email,
                displayName = user.displayName,
                rawToken = rawToken,
                expiresAt = expiresAt,
            ),
        )
        return PasswordResetRequestedResponse()
    }

    /** 잠근 일회성 토큰을 소비하고 비밀번호와 인증 버전을 한 트랜잭션에서 변경합니다. */
    @Transactional
    fun confirmPasswordReset(request: PasswordResetConfirmRequest) {
        val now = Instant.now()
        val resetToken =
            passwordResetTokenRepository.findByTokenHashForUpdate(hashResetToken(request.token.trim()))
                ?: throw InvalidPasswordResetTokenException()
        if (!resetToken.isUsableAt(now) || resetToken.user.deletedAt != null) throw InvalidPasswordResetTokenException()

        val emailAccount =
            authAccountRepository.findByUserIdAndProvider(resetToken.user.id, AuthProvider.EMAIL)
                ?: throw InvalidPasswordResetTokenException()
        val currentHash = emailAccount.passwordHash ?: throw InvalidPasswordResetTokenException()
        if (passwordEncoder.matches(request.newPassword, currentHash)) throw PasswordReuseNotAllowedException()

        emailAccount.changePassword(passwordEncoder.encode(request.newPassword), now)
        resetToken.markUsed(now)
        resetToken.user.invalidateSessions(now)
        passwordResetTokenRepository.revokeAllActiveByUserId(resetToken.user.id, now)
    }

    @Transactional(readOnly = true)
    fun me(userId: UUID): UserDto {
        val user = userRepository.findByIdAndDeletedAtIsNull(userId) ?: throw InvalidCredentialsException()
        return UserDto.from(user)
    }

    /** 연속 공백을 정리한 표시 이름만 변경하고 이메일과 인증 버전은 건드리지 않습니다. */
    @Transactional
    fun updateProfile(userId: UUID, request: UpdateMyProfileRequest): UserDto {
        val normalizedDisplayName = request.displayName.trim().replace(WHITESPACE, " ")
        if (normalizedDisplayName.length !in 1..MAX_DISPLAY_NAME_LENGTH) throw InvalidDisplayNameException()
        val user = userRepository.findActiveByIdForUpdate(userId) ?: throw InvalidCredentialsException()
        user.updateDisplayName(normalizedDisplayName, Instant.now())
        return UserDto.from(user)
    }

    /** 현재 비밀번호 재확인 후 로그인 수단을 제거하고 사용자 직접 식별 정보를 익명화합니다. */
    @Transactional
    fun deleteAccount(userId: UUID, authenticatedAt: Instant, request: DeleteAccountRequest) {
        if (request.confirmation != ACCOUNT_DELETION_CONFIRMATION) throw AccountDeletionConfirmationException()
        if (Duration.between(authenticatedAt, Instant.now()).seconds > SENSITIVE_ACTION_MAX_AGE_SECONDS) {
            throw AccountDeletionReauthenticationException()
        }

        val user = userRepository.findActiveByIdForUpdate(userId) ?: throw InvalidCredentialsException()
        val emailAccount = authAccountRepository.findByUserIdAndProvider(user.id, AuthProvider.EMAIL)
        if (emailAccount != null) {
            val password = request.currentPassword ?: throw AccountDeletionReauthenticationException()
            val passwordHash = emailAccount.passwordHash ?: throw AccountDeletionReauthenticationException()
            if (!passwordEncoder.matches(password, passwordHash)) throw AccountDeletionReauthenticationException()
        }

        val now = Instant.now()
        passwordResetTokenRepository.revokeAllActiveByUserId(user.id, now)
        refreshTokenRepository.revokeAllByUserId(user.id, now)
        authAccountRepository.deleteAllByUserId(user.id)
        personalDataEraser.erase(user.id, user.email)
        user.withdraw(now)
        userRepository.saveAndFlush(user)
    }

    private fun findOrCreateSocialUser(profile: SocialUserProfile): UserEntity {
        val normalizedEmail = profile.email?.let(::normalizeEmail)
        if (normalizedEmail != null) {
            if (!profile.emailVerified) throw SocialEmailNotVerifiedException()
            if (userRepository.findByEmailAndDeletedAtIsNull(normalizedEmail) != null) {
                throw SocialAccountLinkRequiredException()
            }
        }

        return userRepository.save(
            UserEntity(
                email = normalizedEmail,
                displayName = profile.displayName?.trim()?.takeIf { it.isNotBlank() } ?: defaultSocialDisplayName(profile.provider),
            ),
        )
    }

    private fun issueLoginResponse(user: UserEntity, existingRefreshToken: String? = null): UserLoginResponse {
        val rawRefreshToken = existingRefreshToken ?: generateOpaqueToken()
        if (existingRefreshToken == null) {
            refreshTokenRepository.save(
                RefreshTokenEntity(
                    user = user,
                    tokenHash = hashOpaqueToken(rawRefreshToken),
                    expiresAt = Instant.now().plusSeconds(jwtProperties.refreshTokenTtlSeconds),
                ),
            )
        }
        val token = jwtTokenProvider.issueAccessToken(user)
        return UserLoginResponse(
            token = token.accessToken,
            refreshToken = rawRefreshToken,
            tokenType = token.tokenType,
            expiresIn = token.expiresIn,
            expiresAt = token.expiresAt,
            user = UserDto.from(user),
        )
    }

    private fun generateResetToken(): String =
        ByteArray(RESET_TOKEN_BYTES)
            .also(secureRandom::nextBytes)
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    private fun generateOpaqueToken(): String =
        ByteArray(REFRESH_TOKEN_BYTES)
            .also(secureRandom::nextBytes)
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    private fun generateSocialNonce(): String =
        ByteArray(SOCIAL_NONCE_BYTES)
            .also(secureRandom::nextBytes)
            .let { MessageDigest.getInstance("SHA-256").digest(it) }
            .joinToString("") { "%02x".format(it) }

    private fun consumeSocialLoginChallenge(challengeId: UUID?, tokenNonce: String?) {
        val id = challengeId ?: throw MissingSocialLoginChallengeException()
        val nonce = tokenNonce?.takeIf { it.isNotBlank() } ?: throw InvalidSocialLoginChallengeException()
        val now = Instant.now()
        val challenge = socialLoginChallengeRepository.findByIdForUpdate(id)
            ?: throw InvalidSocialLoginChallengeException()
        val actualHash = hashOpaqueToken(nonce).toByteArray(StandardCharsets.UTF_8)
        val expectedHash = challenge.nonceHash.toByteArray(StandardCharsets.UTF_8)
        if (!challenge.isUsableAt(now) || !MessageDigest.isEqual(actualHash, expectedHash)) {
            throw InvalidSocialLoginChallengeException()
        }
        challenge.consume(now)
    }

    private fun hashOpaqueToken(rawToken: String): String = hashResetToken(rawToken)

    private fun hashResetToken(rawToken: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(rawToken.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun defaultSocialDisplayName(provider: AuthProvider): String =
        "${provider.name.lowercase(Locale.US).replaceFirstChar { it.uppercase(Locale.US) }} User"

    private fun normalizeEmail(email: String): String = email.trim().lowercase()

    companion object {
        private val WHITESPACE = Regex("\\s+")
        private const val MAX_DISPLAY_NAME_LENGTH = 40
        private const val RESET_TOKEN_BYTES = 32
        private const val REFRESH_TOKEN_BYTES = 48
        private const val SOCIAL_NONCE_BYTES = 32
        private const val ACCOUNT_DELETION_CONFIRMATION = "DELETE"
        private const val SENSITIVE_ACTION_MAX_AGE_SECONDS = 300L
    }
}

/** 이미 가입된 이메일로 회원가입을 시도했을 때 발생하는 예외입니다. */
class EmailAlreadyRegisteredException : RuntimeException("Email is already registered")

/** 이메일 또는 비밀번호가 올바르지 않을 때 발생하는 예외입니다. */
class InvalidCredentialsException : RuntimeException("Invalid email or password")

/** 지원하지 않는 소셜 제공자로 로그인을 요청했을 때 발생하는 예외입니다. */
class UnsupportedSocialProviderException : RuntimeException("Unsupported social provider")

/** 검증되지 않은 이메일로 기존 계정 연결을 시도할 때 발생하는 예외입니다. */
class SocialEmailNotVerifiedException : RuntimeException("Social email is not verified")

/** 이미 존재하는 이메일 계정에 소셜 계정을 명시적 확인 없이 자동 연결하려 할 때 발생합니다. */
class SocialAccountLinkRequiredException : RuntimeException("Existing account must be authenticated before social linking")

/** Google/Apple 로그인에 서버 발급 challenge가 누락됐습니다. */
class MissingSocialLoginChallengeException : RuntimeException("Social login challenge is required")

/** 만료·재사용·nonce 불일치로 OIDC challenge를 검증할 수 없습니다. */
class InvalidSocialLoginChallengeException : RuntimeException("Social login challenge is invalid")

/** 사용할 수 없거나 만료된 refresh token입니다. */
open class InvalidRefreshTokenException : RuntimeException("Refresh token is invalid or expired")

/** 이미 소비된 refresh token이 다시 제출되어 token family 전체를 폐기했음을 나타냅니다. */
class RefreshTokenReuseDetectedException : InvalidRefreshTokenException()

/** 만료되었거나 이미 사용된 비밀번호 재설정 토큰을 제출했을 때 발생하는 예외입니다. */
class InvalidPasswordResetTokenException : RuntimeException("Password reset token is invalid or expired")

/** 기존 비밀번호와 같은 값으로 재설정하려 할 때 발생하는 예외입니다. */
class PasswordReuseNotAllowedException : RuntimeException("New password must be different from current password")

/** 회원탈퇴 확인 문자열이 올바르지 않을 때 발생하는 예외입니다. */
class AccountDeletionConfirmationException : RuntimeException("Account deletion confirmation is invalid")

/** 회원탈퇴 시 이메일 계정의 현재 비밀번호 재인증에 실패했을 때 발생하는 예외입니다. */
class AccountDeletionReauthenticationException : RuntimeException("Current password is required or invalid")

/** HTTP 계층 밖에서 호출되더라도 표시 이름 정책 위반을 안정적인 인증 오류로 전달합니다. */
class InvalidDisplayNameException : RuntimeException("Display name must be between 1 and 40 characters")
