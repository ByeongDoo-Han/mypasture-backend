package com.example.mypasture.ai

import com.example.mypasture.ai.application.AskBibleAssistantUseCase
import com.example.mypasture.ai.application.AiGroundingNotFoundException
import com.example.mypasture.ai.application.AiUsagePort
import com.example.mypasture.ai.application.TextEmbeddingPort
import com.example.mypasture.ai.domain.RagRetrievalStrategy
import com.example.mypasture.ai.domain.AiAssistantStage
import com.example.mypasture.ai.domain.BibleQuestionIntent
import com.example.mypasture.ai.infrastructure.AiUsageLogRepository
import com.example.mypasture.auth.infrastructure.UserRepository
import com.example.mypasture.user.domain.SubscriptionTier
import com.example.mypasture.user.domain.UserEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.Executors

/** 개발 환경 AI가 실제 성경 원문을 근거로 답하고 사용량을 기록하는지 검증합니다. */
@SpringBootTest
@TestPropertySource(
    properties = [
        "app.bible.kor1910-html-path=src/test/resources/bible/kor_html",
        "app.bible.import-enabled=true",
        "app.ai.mock-enabled=true",
        "app.ai.rag-lexical-fallback-enabled=true",
    ],
)
class AiAssistantFlowTests(
    @Autowired private val userRepository: UserRepository,
    @Autowired private val useCase: AskBibleAssistantUseCase,
    @Autowired private val usageRepository: AiUsageLogRepository,
    @Autowired private val embeddingPort: TextEmbeddingPort,
    @Autowired private val usagePort: AiUsagePort,
) {
    @Test
    fun `local assistant returns grounded answer and records usage`() {
        val user = userRepository.save(UserEntity(email = "ai-${UUID.randomUUID()}@example.com", displayName = "AI Test"))

        val answer = useCase.ask(user.id, SubscriptionTier.FREE, "창세기를 설명해주세요")

        assertTrue(answer.answer.isNotBlank())
        assertTrue(answer.citations.isNotEmpty())
        assertTrue(answer.citations.all { it.text.isNotBlank() })
        assertTrue(answer.citations.all { it.verseId > 0 })
        assertEquals(RagRetrievalStrategy.LEXICAL_FALLBACK, answer.retrievalStrategy)
        assertEquals(0, answer.embeddingTokens)
        assertEquals(BigDecimal.ZERO, answer.estimatedCostUsd)
        assertEquals(1, usageRepository.countByUserIdAndCreatedAtGreaterThanEqual(user.id, java.time.Instant.EPOCH))
    }

    @Test
    fun `assistant refuses to answer when no grounded verse exists`() {
        val user = userRepository.save(UserEntity(email = "ai-empty-${UUID.randomUUID()}@example.com", displayName = "AI Empty"))

        assertThrows(AiGroundingNotFoundException::class.java) {
            useCase.ask(user.id, SubscriptionTier.FREE, "zxqvnoresulttoken")
        }
        assertEquals("FAILED", usageRepository.findAllByUserId(user.id).single().status.name)
    }

    @Test
    fun `explicit bible reference returns the exact database verse`() {
        val user = userRepository.save(UserEntity(email = "ai-reference-${UUID.randomUUID()}@example.com", displayName = "AI Reference"))

        val answer = useCase.ask(user.id, SubscriptionTier.FREE, "창세기 1장 1절을 설명해줘")

        assertEquals(RagRetrievalStrategy.DIRECT_REFERENCE, answer.retrievalStrategy)
        assertEquals(BibleQuestionIntent.BIBLE_EXPLANATION, answer.questionIntent)
        assertEquals(1, answer.citations.size)
        assertEquals("창세기", answer.citations.single().book)
        assertEquals(1, answer.citations.single().chapter)
        assertEquals(1, answer.citations.single().verse)
    }

    @Test
    fun `local embedding is deterministic and matches configured dimensions`() {
        val first = embeddingPort.embed(listOf("불안할 때의 말씀"))
        val second = embeddingPort.embed(listOf("불안할 때의 말씀"))

        assertEquals(1_536, first.vectors.single().size)
        assertTrue(first.vectors.single().contentEquals(second.vectors.single()))
        assertTrue(first.promptTokens > 0)
    }

    @Test
    fun `streaming assistant emits deltas and records grounded answer`() {
        val user = userRepository.save(UserEntity(email = "ai-stream-${UUID.randomUUID()}@example.com", displayName = "AI Stream"))
        val deltas = mutableListOf<String>()

        val answer = useCase.stream(user.id, SubscriptionTier.FREE, "창세기 말씀을 설명해주세요") { deltas += it }

        assertEquals(answer.answer, deltas.joinToString(""))
        assertTrue(answer.citations.isNotEmpty())
        assertTrue(answer.citations.all { answer.answer.contains("[${it.verseId}]") })
        assertEquals(1, usageRepository.countByUserIdAndCreatedAtGreaterThanEqual(user.id, java.time.Instant.EPOCH))
    }

    @Test
    fun `streaming reports question understanding search and generation in order`() {
        val user = userRepository.save(UserEntity(email = "ai-stage-${UUID.randomUUID()}@example.com", displayName = "AI Stage"))
        val stages = mutableListOf<AiAssistantStage>()

        useCase.stream(
            userId = user.id,
            subscriptionTier = SubscriptionTier.FREE,
            question = "창세기 1장 2절의 내용을 단계별로 설명해주세요 ${UUID.randomUUID()}",
            onStage = stages::add,
            onDelta = {},
        )

        assertEquals(
            listOf(
                AiAssistantStage.UNDERSTANDING_QUESTION,
                AiAssistantStage.SEARCHING_SCRIPTURE,
                AiAssistantStage.GENERATING_ANSWER,
            ),
            stages,
        )
    }

    @Test
    fun `daily usage returns free remaining count and premium unlimited policy`() {
        val user = userRepository.save(UserEntity(email = "ai-usage-${UUID.randomUUID()}@example.com", displayName = "AI Usage"))

        val initial = useCase.dailyUsage(user.id, SubscriptionTier.FREE)
        assertEquals(10L, initial.limit)
        assertEquals(0L, initial.used)
        assertEquals(10L, initial.remaining)
        assertEquals(false, initial.unlimited)

        useCase.ask(user.id, SubscriptionTier.FREE, "창세기를 설명해주세요")
        val afterRequest = useCase.dailyUsage(user.id, SubscriptionTier.FREE)
        assertEquals(1L, afterRequest.used)
        assertEquals(9L, afterRequest.remaining)

        val premium = useCase.dailyUsage(user.id, SubscriptionTier.PREMIUM)
        assertEquals(null, premium.limit)
        assertEquals(null, premium.remaining)
        assertTrue(premium.unlimited)
    }

    @Test
    fun `concurrent quota reservations cannot exceed the free daily limit`() {
        val user = userRepository.save(UserEntity(email = "ai-concurrent-${UUID.randomUUID()}@example.com", displayName = "AI Concurrent"))
        val executor = Executors.newFixedThreadPool(12)
        try {
            val reservations = (1..12).map { index ->
                executor.submit<UUID?> { usagePort.reserve(user.id, "동시 질문 $index", 10) }
            }.map { it.get() }

            assertEquals(10, reservations.count { it != null })
            assertEquals(10, usagePort.countToday(user.id))
        } finally {
            executor.shutdownNow()
        }
    }
}
