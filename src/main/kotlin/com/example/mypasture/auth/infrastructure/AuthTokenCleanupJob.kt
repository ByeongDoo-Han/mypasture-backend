package com.example.mypasture.auth.infrastructure

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit

/** 만료된 소셜 challenge와 보존 기간이 지난 refresh token을 정기적으로 삭제합니다. */
@Component
class AuthTokenCleanupJob(
    private val refreshTokenRepository: RefreshTokenRepository,
    private val socialLoginChallengeRepository: SocialLoginChallengeRepository,
    private val emailSignupVerificationRepository: EmailSignupVerificationRepository,
) {
    @Scheduled(fixedDelayString = "\${app.auth.token-cleanup-delay-ms:3600000}")
    @Transactional
    fun cleanup() {
        val now = Instant.now()
        socialLoginChallengeRepository.deleteExpiredBefore(now)
        emailSignupVerificationRepository.deleteExpiredBefore(now.minus(1, ChronoUnit.DAYS))
        // 재사용 탐지를 위해 refresh token은 만료 뒤에도 7일간 보존합니다.
        refreshTokenRepository.deleteExpiredBefore(now.minus(7, ChronoUnit.DAYS))
    }
}
