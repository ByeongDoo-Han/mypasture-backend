package com.example.mypasture.ai.infrastructure

import com.example.mypasture.ai.application.AiCachePort
import com.example.mypasture.ai.domain.GroundedAnswer
import com.example.mypasture.config.AiProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
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
        val payload = redis.opsForValue().get(key(userId, question, cacheScope)) ?: return null
        objectMapper.readValue<GroundedAnswer>(payload)
    }.onFailure { logger.warn("AI cache lookup failed; continuing without cache", it) }.getOrNull()

    override fun put(userId: UUID, question: String, cacheScope: String, answer: GroundedAnswer) {
        runCatching {
            redis.opsForValue().set(key(userId, question, cacheScope), objectMapper.writeValueAsString(answer), Duration.ofSeconds(properties.cacheTtlSeconds))
        }.onFailure { logger.warn("AI cache write failed; response remains available", it) }
    }

    private fun key(userId: UUID, question: String, cacheScope: String): String {
        val scopedInput = "$cacheScope:${properties.commentaryPromptVersion}:$userId:${question.lowercase().trim()}"
        val digest = Mac.getInstance("HmacSHA256")
            .apply { init(SecretKeySpec(properties.privacyHashSecret.toByteArray(), "HmacSHA256")) }
            .doFinal(scopedInput.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "ai:answer:$digest"
    }

    companion object {
        private val logger = LoggerFactory.getLogger(RedisAiCacheAdapter::class.java)
    }
}
