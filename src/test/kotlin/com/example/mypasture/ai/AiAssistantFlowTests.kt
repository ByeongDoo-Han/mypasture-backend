package com.example.mypasture.ai

import com.example.mypasture.ai.application.AskBibleAssistantUseCase
import com.example.mypasture.ai.application.AiGroundingNotFoundException
import com.example.mypasture.ai.application.AiUsagePort
import com.example.mypasture.ai.application.BibleQuestionUnderstandingService
import com.example.mypasture.ai.application.BibleRetrievalPort
import com.example.mypasture.ai.application.AiAnswerFeedbackService
import com.example.mypasture.ai.domain.AiFeedbackRating
import com.example.mypasture.ai.domain.AiFeedbackReason
import com.example.mypasture.ai.domain.AiFeedbackTargetNotFoundException
import com.example.mypasture.ai.infrastructure.AiAnswerFeedbackRepository
import com.example.mypasture.ai.application.TextEmbeddingPort
import com.example.mypasture.ai.domain.RagRetrievalStrategy
import com.example.mypasture.ai.domain.AiAssistantStage
import com.example.mypasture.ai.domain.BibleQuestionIntent
import com.example.mypasture.ai.domain.AiConversationUnavailableException
import com.example.mypasture.ai.domain.AiClientRequestStatus
import com.example.mypasture.ai.domain.AiIdempotencyConflictException
import com.example.mypasture.ai.domain.AiUsageReservation
import com.example.mypasture.ai.infrastructure.AiUsageLogRepository
import com.example.mypasture.ai.infrastructure.AiConversationRepository
import com.example.mypasture.ai.infrastructure.AiRequestReceiptMaintenanceService
import com.example.mypasture.ai.infrastructure.AiRequestReceiptRepository
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
import kotlinx.coroutines.test.runTest

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
    @Autowired private val conversationRepository: AiConversationRepository,
    @Autowired private val feedbackService: AiAnswerFeedbackService,
    @Autowired private val feedbackRepository: AiAnswerFeedbackRepository,
    @Autowired private val receiptMaintenanceService: AiRequestReceiptMaintenanceService,
    @Autowired private val receiptRepository: AiRequestReceiptRepository,
    @Autowired private val questionUnderstandingService: BibleQuestionUnderstandingService,
    @Autowired private val retrievalPort: BibleRetrievalPort,
) {
    @Test
    fun `로컬 AI 목자는 근거 있는 답변을 반환하고 사용량을 기록한다`() {
        val user = userRepository.save(UserEntity(email = "ai-${UUID.randomUUID()}@example.com", displayName = "AI Test"))

        val answer = useCase.ask(user.id, SubscriptionTier.FREE, "창세기를 설명해주세요")

        assertTrue(answer.answer.isNotBlank())
        assertTrue(answer.citations.isNotEmpty())
        assertTrue(answer.citations.all { it.text.isNotBlank() })
        assertTrue(answer.citations.all { it.verseId > 0 })
        assertEquals(RagRetrievalStrategy.LEXICAL_FALLBACK, answer.retrievalStrategy)
        assertEquals(0, answer.embeddingTokens)
        assertEquals(BigDecimal.ZERO, answer.estimatedCostUsd)
        assertTrue(answer.prayer != null)
        assertEquals(null, answer.followUpQuestion)
        assertEquals(null, answer.conversationId)
        assertEquals(1, usageRepository.countByUserIdAndCreatedAtGreaterThanEqual(user.id, java.time.Instant.EPOCH))
        assertEquals(answer.turnId, usageRepository.findAllByUserId(user.id).single().id)
    }

    @Test
    fun `평가를 멱등하게 갱신하고 재생성은 사용 한도를 소비하지 않는다`() = runTest {
        val user = userRepository.save(UserEntity(email = "ai-feedback-${UUID.randomUUID()}@example.com", displayName = "AI Feedback"))
        val question = "창세기 1장 1절을 설명해주세요"
        val original = useCase.ask(user.id, SubscriptionTier.FREE, question)

        feedbackService.submit(
            user.id,
            original.turnId,
            AiFeedbackRating.NOT_HELPFUL,
            setOf(AiFeedbackReason.IRRELEVANT_SCRIPTURE),
            "질문과 조금 멀게 느껴졌어요.",
        )
        feedbackService.submit(
            user.id,
            original.turnId,
            AiFeedbackRating.NOT_HELPFUL,
            setOf(AiFeedbackReason.TOO_GENERIC),
            null,
        )
        val regenerated = feedbackService.regenerate(user.id, SubscriptionTier.FREE, original.turnId, question)

        assertEquals(1, feedbackRepository.count())
        assertEquals(1, usagePort.countToday(user.id))
        assertEquals(2, usageRepository.findAllByUserId(user.id).size)
        val regenerationUsage = usageRepository.findById(regenerated.turnId).orElseThrow()
        assertEquals(false, regenerationUsage.quotaCounted)
        assertEquals(original.turnId, regenerationUsage.parentTurnId)
        assertEquals("COMPLETED", feedbackRepository.findByUserIdAndTurnId(user.id, original.turnId)?.regenerationStatus?.name)
    }

    @Test
    fun `다른 사용자는 답변을 평가할 수 없다`() {
        val owner = userRepository.save(UserEntity(email = "ai-feedback-owner-${UUID.randomUUID()}@example.com", displayName = "Owner"))
        val stranger = userRepository.save(UserEntity(email = "ai-feedback-stranger-${UUID.randomUUID()}@example.com", displayName = "Stranger"))
        val answer = useCase.ask(owner.id, SubscriptionTier.FREE, "창세기를 설명해주세요 ${UUID.randomUUID()}")

        assertThrows(AiFeedbackTargetNotFoundException::class.java) {
            feedbackService.submit(
                stranger.id,
                answer.turnId,
                AiFeedbackRating.HELPFUL,
                emptySet(),
                null,
            )
        }
    }

    @Test
    fun `근거 구절이 없으면 AI 목자는 답변을 거부한다`() {
        val user = userRepository.save(UserEntity(email = "ai-empty-${UUID.randomUUID()}@example.com", displayName = "AI Empty"))

        assertThrows(AiGroundingNotFoundException::class.java) {
            useCase.ask(user.id, SubscriptionTier.FREE, "요한복음 99장 99절을 설명해주세요")
        }
        assertEquals("FAILED", usageRepository.findAllByUserId(user.id).single().status.name)
        assertEquals(0, usagePort.countToday(user.id))
    }

    @Test
    fun `명시한 성경 위치에는 DB의 정확한 구절을 반환한다`() {
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
    fun `명시한 구절 범위는 DB의 모든 절을 순서대로 조회한다`() = runTest {
        val question = questionUnderstandingService.understand("창세기 1:1 ~ 1:2를 설명해줘")

        val retrieval = retrievalPort.searchRelevantVerses(question, 1)

        assertEquals(RagRetrievalStrategy.DIRECT_REFERENCE, retrieval.strategy)
        assertEquals(listOf(1, 2), retrieval.citations.map { it.verse })
        assertTrue(retrieval.citations.all { it.book == "창세기" && it.chapter == 1 })
    }

    @Test
    fun `로컬 임베딩은 같은 입력에 같은 결과를 내고 설정한 차원과 일치한다`() = runTest {
        val first = embeddingPort.embed(listOf("불안할 때의 말씀"))
        val second = embeddingPort.embed(listOf("불안할 때의 말씀"))

        assertEquals(1_536, first.vectors.single().size)
        assertTrue(first.vectors.single().contentEquals(second.vectors.single()))
        assertTrue(first.promptTokens > 0)
    }

    @Test
    fun `스트리밍 답변은 변경분을 전송하고 근거 있는 답변을 기록한다`() {
        val user = userRepository.save(UserEntity(email = "ai-stream-${UUID.randomUUID()}@example.com", displayName = "AI Stream"))
        val deltas = mutableListOf<String>()

        val answer = useCase.stream(user.id, SubscriptionTier.FREE, "창세기 말씀을 설명해주세요") { deltas += it }

        assertEquals(answer.answer, deltas.joinToString(""))
        assertTrue(answer.citations.isNotEmpty())
        assertTrue(answer.citations.all { answer.answer.contains("[${it.verseId}]") })
        assertEquals(1, usageRepository.countByUserIdAndCreatedAtGreaterThanEqual(user.id, java.time.Instant.EPOCH))
    }

    @Test
    fun `스트리밍은 질문 이해 검색 생성 단계를 순서대로 알린다`() {
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
                AiAssistantStage.REVIEWING_ANSWER,
            ),
            stages,
        )
    }

    @Test
    fun `일일 사용량은 무료 잔여 횟수와 프리미엄 무제한 정책을 보여준다`() {
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
    fun `프리미엄 대화는 암호화된 제한 길이 문맥을 유지한다`() {
        val user = userRepository.save(UserEntity(email = "ai-premium-${UUID.randomUUID()}@example.com", displayName = "AI Premium"))

        val first = useCase.ask(user.id, SubscriptionTier.PREMIUM, "창세기를 설명해주세요")
        val second = useCase.ask(user.id, SubscriptionTier.PREMIUM, "창세기 1장 1절을 더 설명해주세요", first.conversationId)

        assertTrue(first.conversationId != null)
        assertEquals(first.conversationId, second.conversationId)
        assertTrue(first.followUpQuestion != null)
        assertEquals("GUIDED_DIALOGUE", first.interactionMode.name)
        val stored = conversationRepository.findById(first.conversationId!!).orElseThrow()
        assertEquals(2, stored.turnCount)
        assertTrue(!stored.encryptedContext.contains("창세기"))
    }

    @Test
    fun `다른 사용자는 프리미엄 대화를 이어갈 수 없다`() {
        val owner = userRepository.save(UserEntity(email = "ai-owner-${UUID.randomUUID()}@example.com", displayName = "Owner"))
        val stranger = userRepository.save(UserEntity(email = "ai-stranger-${UUID.randomUUID()}@example.com", displayName = "Stranger"))
        val first = useCase.ask(owner.id, SubscriptionTier.PREMIUM, "창세기를 설명해주세요")

        assertThrows(AiConversationUnavailableException::class.java) {
            useCase.ask(stranger.id, SubscriptionTier.PREMIUM, "이어서 설명해주세요", first.conversationId)
        }
    }

    @Test
    fun `같은 클라이언트 요청 ID는 사용 한도를 중복 소비하지 않고 완료 답변을 재전송한다`() {
        val user = userRepository.save(UserEntity(email = "ai-idempotent-${UUID.randomUUID()}@example.com", displayName = "AI Idempotent"))
        val clientRequestId = UUID.randomUUID()

        val first = useCase.ask(user.id, SubscriptionTier.FREE, "창세기 1장 1절을 설명해주세요", clientRequestId = clientRequestId)
        val replayed = useCase.ask(user.id, SubscriptionTier.FREE, "창세기 1장 1절을 설명해주세요", clientRequestId = clientRequestId)

        assertEquals(first, replayed)
        assertEquals(1, usageRepository.findAllByUserId(user.id).size)
        assertEquals(1, usagePort.countToday(user.id))
        assertEquals(AiClientRequestStatus.COMPLETED, useCase.requestStatus(user.id, clientRequestId).status)
    }

    @Test
    fun `같은 클라이언트 요청 ID를 다른 질문에 재사용할 수 없다`() {
        val user = userRepository.save(UserEntity(email = "ai-conflict-${UUID.randomUUID()}@example.com", displayName = "AI Conflict"))
        val clientRequestId = UUID.randomUUID()
        useCase.ask(user.id, SubscriptionTier.FREE, "창세기 1장 1절을 설명해주세요", clientRequestId = clientRequestId)

        assertThrows(AiIdempotencyConflictException::class.java) {
            useCase.ask(user.id, SubscriptionTier.FREE, "요한복음 3장 16절을 설명해주세요", clientRequestId = clientRequestId)
        }
        assertEquals(1, usageRepository.findAllByUserId(user.id).size)
        assertEquals(1, usagePort.countToday(user.id))
    }

    @Test
    fun `오래 대기한 요청은 사용 한도를 돌려주고 실패를 기록한다`() {
        val user = userRepository.save(UserEntity(email = "ai-stale-${UUID.randomUUID()}@example.com", displayName = "AI Stale"))
        val clientRequestId = UUID.randomUUID()
        val reservation = usagePort.reserve(
            userId = user.id,
            question = "처리가 멈춘 질문",
            dailyLimit = 10,
            clientRequestId = clientRequestId,
        )
        assertTrue(reservation is AiUsageReservation.Started)

        val receipt = receiptRepository.findByUserIdAndClientRequestId(user.id, clientRequestId)!!
        receipt.updatedAt = java.time.Instant.now().minusSeconds(121)
        receiptRepository.saveAndFlush(receipt)
        receiptMaintenanceService.failStalePendingRequests()

        assertEquals(0, usagePort.countToday(user.id))
        assertEquals(AiClientRequestStatus.FAILED, useCase.requestStatus(user.id, clientRequestId).status)
        assertEquals("AI_REQUEST_STALE", useCase.requestStatus(user.id, clientRequestId).errorCode)
    }

    @Test
    fun `동시 사용 한도 예약은 무료 일일 한도를 넘지 않는다`() {
        val user = userRepository.save(UserEntity(email = "ai-concurrent-${UUID.randomUUID()}@example.com", displayName = "AI Concurrent"))
        val executor = Executors.newFixedThreadPool(12)
        try {
            val reservations = (1..12).map { index ->
                executor.submit<AiUsageReservation?> { usagePort.reserve(user.id, "동시 질문 $index", 10) }
            }.map { it.get() }

            assertEquals(10, reservations.count { it != null })
            assertEquals(10, usagePort.countToday(user.id))
        } finally {
            executor.shutdownNow()
        }
    }
}
