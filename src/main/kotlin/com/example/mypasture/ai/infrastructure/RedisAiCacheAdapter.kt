package com.example.mypasture.ai.infrastructure

import com.example.mypasture.ai.application.AiCachePort
import com.example.mypasture.ai.domain.GroundedAnswer
import com.example.mypasture.config.AiProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Duration

/** Redis 장애 시 원본 AI 흐름을 유지하면서 반복 질문 응답을 캐싱합니다. */
@Component
class RedisAiCacheAdapter(
    private val redis: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val properties: AiProperties,
) : AiCachePort {
    override fun get(question: String): GroundedAnswer? = runCatching {
        val payload = redis.opsForValue().get(key(question)) ?: return null
        objectMapper.readValue<GroundedAnswer>(payload)
    }.onFailure { logger.warn("AI cache lookup failed; continuing without cache", it) }.getOrNull()

    override fun put(question: String, answer: GroundedAnswer) {
        runCatching {
            redis.opsForValue().set(key(question), objectMapper.writeValueAsString(answer), Duration.ofSeconds(properties.cacheTtlSeconds))
        }.onFailure { logger.warn("AI cache write failed; response remains available", it) }
    }

    private fun key(question: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(question.lowercase().trim().toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "ai:answer:$digest"
    }

    companion object {
        private val logger = LoggerFactory.getLogger(RedisAiCacheAdapter::class.java)
    }
}
