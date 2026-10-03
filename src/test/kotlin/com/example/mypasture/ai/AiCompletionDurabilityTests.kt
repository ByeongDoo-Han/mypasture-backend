package com.example.mypasture.ai

import com.example.mypasture.ai.application.AiCachePort
import com.example.mypasture.ai.application.AiUsagePort
import com.example.mypasture.ai.application.AskBibleAssistantUseCase
import com.example.mypasture.ai.domain.AiClientRequestStatus
import com.example.mypasture.ai.domain.GroundedAnswer
import com.example.mypasture.auth.infrastructure.UserRepository
import com.example.mypasture.user.domain.SubscriptionTier
import com.example.mypasture.user.domain.UserEntity
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 캐시 장애와 SSE 전송 오류를 주입해 저장된 성공 응답이 유지되는지 검증합니다. */
@SpringBootTest(properties = ["app.bible.import-enabled=true", "app.ai.rag-lexical-fallback-enabled=true"])
@Import(AiCompletionDurabilityTests.CacheConfiguration::class)
class AiCompletionDurabilityTests(
    @Autowired private val users: UserRepository,
    @Autowired private val useCase: AskBibleAssistantUseCase,
    @Autowired private val usage: AiUsagePort,
    @Autowired private val cache: FailingWriteCache,
) {
    @Test
    fun `cache write failure still returns and replays the saved answer`() {
        val user = users.save(UserEntity(displayName = "Cache failure"))
        val id = UUID.randomUUID()
        val answer = useCase.ask(user.id, SubscriptionTier.FREE, QUESTION, clientRequestId = id)
        assertTrue(answer.answer.isNotBlank())
        assertEquals(AiClientRequestStatus.COMPLETED, useCase.requestStatus(user.id, id).status)
        assertEquals(answer, useCase.ask(user.id, SubscriptionTier.FREE, QUESTION, clientRequestId = id))
        assertEquals(1, usage.countToday(user.id))
    }

    @Test
    fun `cache hit delivery failure preserves receipt for reconnection`() {
        val user = users.save(UserEntity(displayName = "SSE delivery failure"))
        val answer = useCase.ask(user.id, SubscriptionTier.FREE, QUESTION)
        cache.answers[user.id] = answer
        val id = UUID.randomUUID()
        assertThrows(IllegalStateException::class.java) {
            useCase.stream(user.id, SubscriptionTier.FREE, QUESTION, clientRequestId = id, onDelta = { error("Connection lost") })
        }
        val saved = useCase.requestStatus(user.id, id)
        assertEquals(AiClientRequestStatus.COMPLETED, saved.status)
        assertEquals(answer.answer, saved.answer!!.answer)
        assertEquals(2, usage.countToday(user.id))
    }

    /** 실데이터 캐시 대신 저장 장애를 재현하는 테스트 전용 구성입니다. */
    @TestConfiguration
    class CacheConfiguration {
        @Bean @Primary fun failingWriteCache() = FailingWriteCache()
    }

    /** 읽기 결과는 지정할 수 있고 쓰기는 항상 실패하는 테스트 전용 어댑터입니다. */
    class FailingWriteCache : AiCachePort {
        val answers = ConcurrentHashMap<UUID, GroundedAnswer>()
        override fun get(userId: UUID, question: String, cacheScope: String) = answers[userId]
        override fun put(userId: UUID, question: String, cacheScope: String, answer: GroundedAnswer): Unit =
            throw IllegalStateException("Simulated Redis write failure")
        override fun evictUser(userId: UUID) { answers.remove(userId) }
    }
    companion object { private const val QUESTION = "창세기 1장 1절을 설명해주세요" }
}
