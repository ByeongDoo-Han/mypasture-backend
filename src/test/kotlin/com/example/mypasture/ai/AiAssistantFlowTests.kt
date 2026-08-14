package com.example.mypasture.ai

import com.example.mypasture.ai.application.AskBibleAssistantUseCase
import com.example.mypasture.ai.infrastructure.AiUsageLogRepository
import com.example.mypasture.auth.infrastructure.UserRepository
import com.example.mypasture.user.domain.SubscriptionTier
import com.example.mypasture.user.domain.UserEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource
import java.math.BigDecimal
import java.util.UUID

/** 개발 환경 AI가 실제 성경 원문을 근거로 답하고 사용량을 기록하는지 검증합니다. */
@SpringBootTest
@TestPropertySource(properties = ["app.bible.kor1910-html-path=src/test/resources/bible/kor_html", "app.ai.mock-enabled=true"])
class AiAssistantFlowTests(
    @Autowired private val userRepository: UserRepository,
    @Autowired private val useCase: AskBibleAssistantUseCase,
    @Autowired private val usageRepository: AiUsageLogRepository,
) {
    @Test
    fun `local assistant returns grounded answer and records usage`() {
        val user = userRepository.save(UserEntity(email = "ai-${UUID.randomUUID()}@example.com", displayName = "AI Test"))

        val answer = useCase.ask(user.id, SubscriptionTier.FREE, "창세기를 설명해주세요")

        assertTrue(answer.answer.isNotBlank())
        assertTrue(answer.citations.isNotEmpty())
        assertTrue(answer.citations.all { it.text.isNotBlank() })
        assertEquals(BigDecimal.ZERO, answer.estimatedCostUsd)
        assertEquals(1, usageRepository.countByUserIdAndCreatedAtGreaterThanEqual(user.id, java.time.Instant.EPOCH))
    }
}
