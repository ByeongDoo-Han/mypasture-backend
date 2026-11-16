package com.example.mypasture.ai

import com.example.mypasture.ai.application.AiCachePort
import com.example.mypasture.ai.domain.GroundedAnswer
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.DynamicPropertyRegistry
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 기록 검사에서 Redis 장애와 분리해 캐시 응답 경로를 재현하고 실제 DB를 선택합니다. */
@TestConfiguration
class AiHistoryTestConfiguration {
    @Bean
    @Primary
    fun historyTestCache(): AiCachePort = object : AiCachePort {
        private val answers = ConcurrentHashMap<Triple<UUID, String, String>, GroundedAnswer>()
        override fun get(userId: UUID, question: String, cacheScope: String) = answers[Triple(userId, question, cacheScope)]
        override fun put(userId: UUID, question: String, cacheScope: String, answer: GroundedAnswer) {
            answers[Triple(userId, question, cacheScope)] = answer
        }
        override fun evictUser(userId: UUID) { answers.keys.removeIf { it.first == userId } }
    }

    companion object {
        fun postgres(registry: DynamicPropertyRegistry) {
            val url = System.getenv("AI_HISTORY_TEST_JDBC_URL")?.takeIf(String::isNotBlank) ?: return
            registry.add("spring.datasource.url") { url }
            registry.add("spring.datasource.username") { System.getenv("AI_HISTORY_TEST_JDBC_USER") ?: "postgres" }
            registry.add("spring.datasource.password") { System.getenv("AI_HISTORY_TEST_JDBC_PASSWORD") ?: "" }
            registry.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
            registry.add("spring.flyway.enabled") { "true" }
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
        }
    }
}
