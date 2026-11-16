package com.example.mypasture.prayer.infrastructure

import com.example.mypasture.prayer.application.PrayerConflictException
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** Google 서명 키로 AdMob SSV 원문 쿼리를 검증하고 보상 정보를 추출합니다. */
@Component
class AdmobPrayerRewardVerifier(private val keyProvider: PrayerRewardKeyProvider) {
    fun verify(rawQuery: String): VerifiedPrayerReward {
        val signatureMarker = "&signature="
        val signatureIndex = rawQuery.indexOf(signatureMarker)
        if (signatureIndex <= 0 || rawQuery.indexOf(signatureMarker, signatureIndex + 1) >= 0)
            throw PrayerConflictException("광고 검증 정보가 올바르지 않습니다.")
        val keyMarker = "&key_id="
        val keyIndex = rawQuery.indexOf(keyMarker, signatureIndex + signatureMarker.length)
        if (keyIndex <= signatureIndex || rawQuery.indexOf(keyMarker, keyIndex + 1) >= 0)
            throw PrayerConflictException("광고 검증 키가 올바르지 않습니다.")
        val message = rawQuery.substring(0, signatureIndex)
        val signatureText = rawQuery.substring(signatureIndex + signatureMarker.length, keyIndex)
        val keyId = rawQuery.substring(keyIndex + keyMarker.length).toLongOrNull()
            ?: throw PrayerConflictException("광고 검증 키가 올바르지 않습니다.")
        val publicKey = keyProvider.keys()[keyId] ?: run {
            keyProvider.refresh()[keyId]
        } ?: throw PrayerConflictException("광고 검증 키를 찾을 수 없습니다.")
        val valid = runCatching {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey)
                update(message.toByteArray(StandardCharsets.UTF_8))
                verify(Base64.getUrlDecoder().decode(signatureText))
            }
        }.getOrDefault(false)
        if (!valid) throw PrayerConflictException("광고 검증에 실패했습니다.")
        val parameters = message.split('&').associate { part ->
            val index = part.indexOf('=')
            if (index <= 0) throw PrayerConflictException("광고 검증 정보가 올바르지 않습니다.")
            part.substring(0, index) to URLDecoder.decode(part.substring(index + 1), StandardCharsets.UTF_8)
        }
        val intentId = runCatching { UUID.fromString(parameters["custom_data"]) }.getOrNull()
            ?: throw PrayerConflictException("광고 요청 식별자가 없습니다.")
        val transactionId = parameters["transaction_id"]?.takeIf { it.length in 1..120 }
            ?: throw PrayerConflictException("광고 거래 식별자가 없습니다.")
        val adUnitId = parameters["ad_unit"]?.takeIf { it.length in 1..100 }
            ?: throw PrayerConflictException("광고 단위가 없습니다.")
        val signedAt = parameters["timestamp"]?.toLongOrNull()?.let(Instant::ofEpochMilli)
            ?: throw PrayerConflictException("광고 시각이 없습니다.")
        return VerifiedPrayerReward(intentId, transactionId, adUnitId, signedAt)
    }

}

/** AdMob의 공개 서명 키를 제공하고 알 수 없는 키 ID에는 새 목록을 요청합니다. */
interface PrayerRewardKeyProvider {
    fun keys(): Map<Long, PublicKey>
    fun refresh(): Map<Long, PublicKey>
}

/** Google 공개 키 목록을 제한 시간과 캐시를 적용해 조회합니다. */
@Component
class GooglePrayerRewardKeyProvider(private val mapper: ObjectMapper) : PrayerRewardKeyProvider {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    @Volatile private var cached: Pair<Instant, Map<Long, PublicKey>>? = null

    override fun refresh(): Map<Long, PublicKey> {
        cached = null
        return keys()
    }

    override fun keys(): Map<Long, PublicKey> {
        val now = Instant.now()
        cached?.takeIf { Duration.between(it.first, now) < Duration.ofHours(12) }?.let { return it.second }
        synchronized(this) {
            cached?.takeIf { Duration.between(it.first, now) < Duration.ofHours(12) }?.let { return it.second }
            val response = client.send(
                HttpRequest.newBuilder(URI.create("https://www.gstatic.com/admob/reward/verifier-keys.json"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString(),
            )
            if (response.statusCode() != 200) throw PrayerConflictException("광고 검증 키를 가져올 수 없습니다.")
            val parsed = mapper.readTree(response.body()).path("keys").associate { item ->
                val id = item.path("keyId").asLong()
                val bytes = Base64.getDecoder().decode(item.path("base64").asText())
                id to KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
            }
            if (parsed.isEmpty()) throw PrayerConflictException("광고 검증 키가 비어 있습니다.")
            cached = now to parsed
            return parsed
        }
    }
}

/** 서명 검증이 끝난 광고 보상 콜백 값입니다. */
data class VerifiedPrayerReward(val intentId: UUID, val transactionId: String, val adUnitId: String, val signedAt: Instant)
