package com.example.mypasture.prayer.infrastructure

import com.example.mypasture.prayer.application.PrayerConflictException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.Signature
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** 보상 콜백의 원문 서명·거래 식별자 변조 방지를 독립 키로 검증합니다. */
class AdmobPrayerRewardVerifierTests {
    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val verifier = AdmobPrayerRewardVerifier(object : PrayerRewardKeyProvider {
        override fun keys(): Map<Long, PublicKey> = mapOf(7L to keys.public)
        override fun refresh(): Map<Long, PublicKey> = keys()
    })

    @Test
    fun `valid signed callback preserves intent transaction and ad unit`() {
        val intent = UUID.randomUUID()
        val at = Instant.now()
        val message = "ad_unit=ca-app-pub-1234567890123456/1234567890&custom_data=$intent&timestamp=${at.toEpochMilli()}&transaction_id=tx-123"
        val reward = verifier.verify(signed(message))
        assertEquals(intent, reward.intentId)
        assertEquals("tx-123", reward.transactionId)
        assertEquals("ca-app-pub-1234567890123456/1234567890", reward.adUnitId)
        assertEquals(at.toEpochMilli(), reward.signedAt.toEpochMilli())
    }

    @Test
    fun `changed signed data or malformed signature cannot grant reward`() {
        val message = "ad_unit=ca-app-pub-1234567890123456/1234567890&custom_data=${UUID.randomUUID()}&timestamp=${Instant.now().toEpochMilli()}&transaction_id=tx-123"
        assertThrows(PrayerConflictException::class.java) { verifier.verify(signed(message).replace("tx-123", "tx-124")) }
        assertThrows(PrayerConflictException::class.java) { verifier.verify("$message&signature=invalid&key_id=7") }
        assertThrows(PrayerConflictException::class.java) { verifier.verify("$message&key_id=7") }
    }

    private fun signed(message: String): String {
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keys.private)
            update(message.toByteArray(StandardCharsets.UTF_8))
            Base64.getUrlEncoder().withoutPadding().encodeToString(sign())
        }
        return "$message&signature=$signature&key_id=7"
    }
}
