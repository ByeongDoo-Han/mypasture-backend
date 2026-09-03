package com.example.mypasture.ai.infrastructure

import com.example.mypasture.ai.application.AiCachePort
import com.example.mypasture.ai.domain.GroundedAnswer
import com.example.mypasture.config.AiProperties
import com.example.mypasture.user.application.AccountDeletedEvent
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.time.Duration
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Redis 장애 시 원본 AI 흐름을 유지하면서 반복 질문 응답을 캐싱합니다. */
@Component
class RedisAiCacheAdapter(
    private val redis: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val properties: AiProperties,
) : AiCachePort {
    override fun get(userId: UUID, question: String, cacheScope: String): GroundedAnswer? = runCatching {
        val answerKey = key(userId, question, cacheScope)
        val payload = redis.opsForValue().get(answerKey) ?: return null
        track(userId, answerKey)
        objectMapper.readValue<GroundedAnswer>(payload)
    }.onFailure { logger.warn("AI cache lookup failed; continuing without cache", it) }.getOrNull()

    override fun put(userId: UUID, question: String, cacheScope: String, answer: GroundedAnswer) {
        runCatching {
            val answerKey = key(userId, question, cacheScope)
            redis.opsForValue().set(answerKey, objectMapper.writeValueAsString(answer), cacheTtl())
            track(userId, answerKey)
        }.onFailure { logger.warn("AI cache write failed; response remains available", it) }
    }

    override fun evictUser(userId: UUID) {
        runCatching {
            val indexKey = userIndexKey(userId)
            val answerKeys = redis.opsForSet().members(indexKey).orEmpty()
            if (answerKeys.isNotEmpty()) redis.delete(answerKeys)
            redis.delete(indexKey)
        }.onFailure { logger.warn("AI cache eviction failed; cached answers will expire by TTL", it) }
    }

    private fun track(userId: UUID, answerKey: String) {
        val indexKey = userIndexKey(userId)
        redis.opsForSet().add(indexKey, answerKey)
        redis.expire(indexKey, cacheTtl())
    }

    private fun cacheTtl(): Duration = Duration.ofSeconds(properties.cacheTtlSeconds)

    private fun userIndexKey(userId: UUID): String = "ai:answer-index:${digest(userId.toString())}"

    private fun key(userId: UUID, question: String, cacheScope: String): String {
        val scopedInput = "$cacheScope:${properties.commentaryPromptVersion}:$userId:${question.lowercase().trim()}"
        return "ai:answer:${digest(scopedInput)}"
    }

    private fun digest(value: String): String = Mac.getInstance("HmacSHA256")
        .apply { init(SecretKeySpec(properties.privacyHashSecret.toByteArray(), "HmacSHA256")) }
        .doFinal(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    companion object {
        private val logger = LoggerFactory.getLogger(RedisAiCacheAdapter::class.java)
    }
}

/** 계정 삭제 트랜잭션이 커밋된 뒤 Redis의 사용자별 AI 캐시를 최선 노력으로 제거합니다. */
@Component
class AccountDeletedAiCacheListener(private val cache: AiCachePort) {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onAccountDeleted(event: AccountDeletedEvent) {
        cache.evictUser(event.userId)
    }
}
