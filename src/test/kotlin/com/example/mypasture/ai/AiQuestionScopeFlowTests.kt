package com.example.mypasture.ai

import com.example.mypasture.ai.application.*
import com.example.mypasture.ai.domain.*
import com.example.mypasture.ai.infrastructure.*
import com.example.mypasture.auth.infrastructure.UserRepository
import com.example.mypasture.user.domain.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.TestPropertySource
import java.math.BigDecimal
import java.util.UUID

/** 관련성 실패의 quota·영수증·cache·대화·재생성·비용 통합 경로를 검증합니다. */
@SpringBootTest
@Import(AiQuestionScopeFlowTests.ScopeTestConfig::class)
@TestPropertySource(properties = ["app.bible.kor1910-html-path=src/test/resources/bible/kor_html", "app.bible.import-enabled=true",
    "app.ai.mock-enabled=true", "app.ai.rag-lexical-fallback-enabled=true", "app.ai.evaluation-enabled=false"])
class AiQuestionScopeFlowTests(
    @Autowired private val users: UserRepository,
    @Autowired private val useCase: AskBibleAssistantUseCase,
    @Autowired private val usages: AiUsageLogRepository,
    @Autowired private val usagePort: AiUsagePort,
    @Autowired private val feedbackService: AiAnswerFeedbackService,
    @Autowired private val feedbacks: AiAnswerFeedbackRepository,
    @Autowired private val scope: ControlledScope,
    @Autowired private val cache: CountingCache,
) {
    @BeforeEach fun reset() { scope.override = null; scope.calls = 0; scope.fail = false; scope.unknownFailure = false; cache.gets = 0 }
    private fun user() = users.save(UserEntity(email = "scope-${UUID.randomUUID()}@example.com", displayName = "Scope"))

    @Test fun `unrelated request fails before cache search and delta while preserving usage and receipt`() {
        val user = user(); val id = UUID.randomUUID(); val stages = mutableListOf<AiAssistantStage>(); val deltas = mutableListOf<String>()
        assertThrows(AiQuestionOutOfScopeException::class.java) {
            useCase.stream(user.id, SubscriptionTier.FREE, "불안하니 비트코인 시세 알려줘", clientRequestId = id, onStage = { stages += it }) { deltas += it }
        }
        assertEquals(listOf(AiAssistantStage.UNDERSTANDING_QUESTION), stages)
        assertTrue(deltas.isEmpty()); assertEquals(0, cache.gets)
        val usage = usages.findAllByUserId(user.id).single()
        assertEquals(AiUsageStatus.FAILED, usage.status); assertEquals("AI_QUESTION_OUT_OF_SCOPE", usage.errorCode)
        assertEquals(BigDecimal("0.000100"), usage.evaluationCostUsd); assertEquals(11, usage.evaluationPromptTokens)
        assertEquals("gpt-5.6-luna", usage.evaluatorModel); assertNull(usage.evaluationPassed)
        assertEquals(0, usagePort.countToday(user.id))
        assertEquals("AI_QUESTION_OUT_OF_SCOPE", useCase.requestStatus(user.id, id).errorCode)
    }

    @Test fun `unclear malformed and downstream grounding failures record assessment cost`() {
        val unclear = user()
        assertThrows(AiQuestionScopeUnclearException::class.java) { useCase.ask(unclear.id, SubscriptionTier.FREE, "그냥") }
        assertEquals("AI_QUESTION_SCOPE_UNCLEAR", usages.findAllByUserId(unclear.id).single().errorCode)
        val malformed = user(); scope.fail = true
        assertThrows(AiQuestionScopeProviderException::class.java) { useCase.ask(malformed.id, SubscriptionTier.FREE, "창세기 말씀") }
        assertEquals(11, usages.findAllByUserId(malformed.id).single().evaluationPromptTokens)
        scope.fail = false
        val noGrounding = user()
        assertThrows(AiGroundingNotFoundException::class.java) { useCase.ask(noGrounding.id, SubscriptionTier.FREE, "요한복음 99장 99절을 설명해주세요") }
        assertEquals(BigDecimal("0.000100"), usages.findAllByUserId(noGrounding.id).single().estimatedCostUsd)
    }

    @Test fun `new cache request assesses again and completed same id replay skips assessment`() {
        val user = user(); val id = UUID.randomUUID(); val question = "창세기 1장 1절을 설명해주세요"
        val first = useCase.ask(user.id, SubscriptionTier.FREE, question, clientRequestId = id)
        assertEquals(1, scope.calls)
        val replay = useCase.ask(user.id, SubscriptionTier.FREE, question, clientRequestId = id)
        assertEquals(first.turnId, replay.turnId); assertEquals(1, scope.calls)
        val second = useCase.ask(user.id, SubscriptionTier.FREE, question)
        assertTrue(second.cacheHit); assertEquals(2, scope.calls)
        assertEquals(11, second.evaluationPromptTokens); assertEquals(BigDecimal("0.0001"), second.evaluationCostUsd)
        assertEquals(BigDecimal("0.0001"), second.estimatedCostUsd); assertEquals(0, second.promptTokens)
        val gets = cache.gets; scope.override = AiQuestionScope.UNRELATED
        assertThrows(AiQuestionOutOfScopeException::class.java) { useCase.ask(user.id, SubscriptionTier.FREE, question) }
        assertEquals(gets, cache.gets)
    }

    @Test fun `owned religious context does not allow topic switch and short followup remains supported`() {
        val user = user(); val first = useCase.ask(user.id, SubscriptionTier.PREMIUM, "창세기 말씀을 설명해주세요")
        val short = useCase.ask(user.id, SubscriptionTier.PREMIUM, "마음의 평안", first.conversationId)
        assertEquals(first.conversationId, short.conversationId); assertNotNull(scope.context)
        assertEquals("마음의 평안", scope.question)
        assertThrows(AiQuestionOutOfScopeException::class.java) { useCase.ask(user.id, SubscriptionTier.PREMIUM, "지금 BTC 시세 알려줘", first.conversationId) }
        val stranger = user(); val calls = scope.calls
        assertThrows(AiConversationUnavailableException::class.java) { useCase.ask(stranger.id, SubscriptionTier.PREMIUM, "말씀", first.conversationId) }
        assertEquals(calls, scope.calls)
    }

    @Test fun `regeneration scope rejection rolls back feedback and records cost without quota`() = runTest {
        val user = user(); val question = "창세기 말씀을 설명해주세요"; val original = useCase.ask(user.id, SubscriptionTier.FREE, question)
        feedbackService.submit(user.id, original.turnId, AiFeedbackRating.NOT_HELPFUL, setOf(AiFeedbackReason.TOO_GENERIC), null)
        scope.override = AiQuestionScope.UNRELATED
        try { feedbackService.regenerate(user.id, SubscriptionTier.FREE, original.turnId, question); fail("Expected scope failure") }
        catch (_: AiQuestionOutOfScopeException) { }
        val failed = usages.findAllByUserId(user.id).single { it.parentTurnId != null }
        assertEquals(AiUsageStatus.FAILED, failed.status); assertEquals("AI_QUESTION_OUT_OF_SCOPE", failed.errorCode)
        assertEquals(11, failed.evaluationPromptTokens); assertEquals(1, usagePort.countToday(user.id))
        assertEquals("FAILED", feedbacks.findByUserIdAndTurnId(user.id, original.turnId)?.regenerationStatus?.name)
    }

    @Test fun `provider outage blocks unrelated classification without keyword fallback or invented usage`() {
        val user = user(); scope.unknownFailure = true
        assertThrows(AiProviderException::class.java) { useCase.ask(user.id, SubscriptionTier.FREE, "창세기 말씀") }
        assertEquals(0, cache.gets)
        val failure = usages.findAllByUserId(user.id).single()
        assertEquals(AiUsageStatus.FAILED, failure.status); assertEquals(0, failure.evaluationPromptTokens)
        assertEquals(BigDecimal("0.000000"), failure.estimatedCostUsd)
    }

    @Test fun `financial background emotional support passes while direct Samsung quote and ambiguous help fail`() {
        val user = user(); val answer = useCase.ask(user.id, SubscriptionTier.FREE, "주가가 떨어져서 마음이 불안해. 창세기 말씀으로 마음을 다잡고 싶어.")
        assertTrue(answer.citations.isNotEmpty())
        assertThrows(AiQuestionOutOfScopeException::class.java) { useCase.ask(user.id, SubscriptionTier.FREE, "삼성전자 주가 좀 알려줘") }
        assertThrows(AiQuestionScopeUnclearException::class.java) { useCase.ask(user.id, SubscriptionTier.FREE, "어떻게 해야 할까?") }
    }

    @Test fun `regeneration completes with assessment cost and keeps crisis priority after ownership`() = runTest {
        val user = user(); val question = "창세기 말씀을 설명해주세요"; val original = useCase.ask(user.id, SubscriptionTier.FREE, question)
        feedbackService.submit(user.id, original.turnId, AiFeedbackRating.NOT_HELPFUL, setOf(AiFeedbackReason.TOO_GENERIC), null)
        val regenerated = feedbackService.regenerate(user.id, SubscriptionTier.FREE, original.turnId, question)
        assertEquals(11, regenerated.evaluationPromptTokens); assertNull(regenerated.evaluationPassed)
        assertEquals(BigDecimal("0.0001"), regenerated.estimatedCostUsd)
        // 정책 추가 전에 완료된 과거 답변도 재생성은 현재 안전 정책을 통과해야 합니다.
        val crisis = "죽고 싶어요"
        val source = usagePort.reserve(user.id, crisis, 100) as AiUsageReservation.Started
        usagePort.complete(source.turnId, original.copy(turnId = source.turnId), crisis, 0)
        val calls = scope.calls
        try { feedbackService.regenerate(user.id, SubscriptionTier.FREE, source.turnId, crisis); fail("Expected safety failure") }
        catch (_: AiSafetyException) { }
        assertEquals(calls, scope.calls)
        val stranger = user()
        try { feedbackService.regenerate(stranger.id, SubscriptionTier.FREE, source.turnId, crisis); fail("Expected ownership failure") }
        catch (_: AiFeedbackRegenerationUnavailableException) { }
    }

    @Test fun `context-only source regeneration asks clarification and preserves original answer`() = runTest {
        val user = user(); val first = useCase.ask(user.id, SubscriptionTier.PREMIUM, "창세기 말씀을 설명해주세요")
        val followup = useCase.ask(user.id, SubscriptionTier.PREMIUM, "네", first.conversationId)
        feedbackService.submit(user.id, followup.turnId, AiFeedbackRating.NOT_HELPFUL, setOf(AiFeedbackReason.TOO_GENERIC), null)
        try { feedbackService.regenerate(user.id, SubscriptionTier.PREMIUM, followup.turnId, "네"); fail("Expected clarification") }
        catch (_: AiQuestionScopeUnclearException) { }
        assertNotNull(usagePort.findCompletedTurn(user.id, followup.turnId))
        assertEquals(2, usagePort.countToday(user.id))
        assertEquals("네", scope.question); assertNull(scope.context)
        val failed = usages.findAllByUserId(user.id).single { it.parentTurnId == followup.turnId }
        assertEquals("AI_QUESTION_SCOPE_UNCLEAR", failed.errorCode)
    }

    @TestConfiguration class ScopeTestConfig {
        @Bean @Primary fun scopePort() = ControlledScope()
        @Bean @Primary fun cachePort() = CountingCache()
    }
    /** 제공자 의미 판단은 별도로 두고 영속 비용과 실패 경로만 제어합니다. */
    class ControlledScope : AiQuestionScopePort {
        var override: AiQuestionScope? = null; var calls = 0; var fail = false; var unknownFailure = false
        var context: AiConversationContext? = null; var question = ""
        override suspend fun assess(currentQuestion: String, conversationContext: AiConversationContext?, config: AiRuntimeConfigSnapshot): AiQuestionScopeAssessment {
            calls++; context = conversationContext; question = currentQuestion
            if (unknownFailure) throw AiProviderException("연결 장애")
            val result = LocalAiCompletionAdapter().assess(currentQuestion, conversationContext, config).copy(
                scope = override ?: LocalAiCompletionAdapter().assess(currentQuestion, conversationContext, config).scope,
                promptTokens = 11, completionTokens = 3, estimatedCostUsd = BigDecimal("0.0001"))
            if (fail) throw AiQuestionScopeProviderException(result.copy(validated = false))
            return result
        }
    }
    /** 과거 캐시를 읽기 전 관련성 정책을 실행했는지 관측합니다. */
    class CountingCache : AiCachePort {
        var gets = 0
        private val answers = mutableMapOf<Triple<UUID, String, String>, GroundedAnswer>()
        override fun get(userId: UUID, question: String, cacheScope: String): GroundedAnswer? { gets++; return answers[Triple(userId, question, cacheScope)] }
        override fun put(userId: UUID, question: String, cacheScope: String, answer: GroundedAnswer) { answers[Triple(userId, question, cacheScope)] = answer }
        override fun evictUser(userId: UUID) { answers.keys.removeIf { it.first == userId } }
    }
}
