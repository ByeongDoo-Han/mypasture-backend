package com.example.mypasture.billing.infrastructure

import com.example.mypasture.billing.domain.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 사용자 행 잠금 안에서 계정과 lease를 조회/저장합니다. */
interface BillingAccountRepository : JpaRepository<BillingAccountEntity, UUID> {
    fun findByAccountToken(accountToken: UUID): BillingAccountEntity?
}

/** 계열 식별자는 store/environment까지 포함한 해시이며 소유자 변경을 허용하지 않습니다. */
interface BillingSourceRepository : JpaRepository<BillingSourceEntity, String>

/** 만료된 lease 작업도 다시 찾고 사용자별 요청 ID로 중복 작업을 차단합니다. */
interface BillingVerificationJobRepository : JpaRepository<BillingVerificationJobEntity, UUID> {
    fun findByUserIdAndRequestId(userId: UUID, requestId: UUID): BillingVerificationJobEntity?
    fun findByIdAndUserId(id: UUID, userId: UUID): BillingVerificationJobEntity?
    fun countByUserIdAndCreatedAtAfter(userId: UUID, cutoff: Instant): Long
    fun findAllByUserIdAndRequestHashAndRefreshEnabledTrue(userId: UUID, requestHash: String): List<BillingVerificationJobEntity>

    @Query("""
        select j from BillingVerificationJobEntity j where j.nextAttemptAt < :now
        and (j.phase in :pending or (j.phase = :complete and j.refreshEnabled = true))
        order by j.nextAttemptAt, j.createdAt
    """)
    fun due(now: Instant, pending: List<BillingVerificationPhase>, complete: BillingVerificationPhase, page: Pageable): List<BillingVerificationJobEntity>
}

/** 구매 증거는 전용 키와 작업 ID AAD로 암호화하고 멱등 비교에는 HMAC만 사용합니다. */
@Component
class BillingProofCipher(private val properties: BillingProperties) {
    private val random = SecureRandom()
    private fun key() = Base64.getDecoder().decode(properties.encryptionKey).also { require(it.size == 32) }
    fun fingerprint(value: String): String = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key(), "HmacSHA256")) }
        .doFinal(value.toByteArray()).joinToString("") { "%02x".format(it) }
    fun encrypt(value: String, id: UUID): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key(), "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(id.toString().toByteArray())
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(value.toByteArray()))
    }
    fun decrypt(value: String, id: UUID): String {
        val bytes = Base64.getDecoder().decode(value)
        require(bytes.size > 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key(), "AES"), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(id.toString().toByteArray())
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }
}
