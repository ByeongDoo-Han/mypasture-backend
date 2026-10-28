package com.example.mypasture.ai

import com.example.mypasture.ai.application.*
import com.example.mypasture.ai.domain.*
import com.example.mypasture.ai.infrastructure.*
import com.example.mypasture.auth.infrastructure.UserRepository
import com.example.mypasture.config.AiProperties
import com.example.mypasture.user.domain.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** 당시 문맥의 AAD·정밀도·불변성과 암호화 메타데이터를 검증합니다. */
class AiContextSnapshotCipherTests {
    private val cipher = AiContextSnapshotCipher(AiProperties(model = "test", embeddingModel = "test", embeddingDimensions = 3,
        embeddingBatchSize = 1, embeddingIndexingEnabled = false, embeddingIndexPollDelayMs = 1_000,
        embeddingInputCostPerMillion = BigDecimal.ZERO, apiKey = "test", baseUrl = "http://localhost",
        maxInputChars = 300, maxOutputTokens = 100, freeDailyLimit = 10, timeoutMs = 1_000,
        cacheTtlSeconds = 60, ragTopK = 3, ragMinSimilarity = 0.1, ragLexicalFallbackEnabled = true,
        commentaryGenerationEnabled = false, commentaryPollDelayMs = 1_000, commentaryPromptVersion = "test",
        commentaryMaxOutputTokens = 100, commentaryInputCostPerMillion = BigDecimal.ZERO,
        commentaryOutputCostPerMillion = BigDecimal.ZERO, privacyHashSecret = "snapshot-test-secret"), jacksonObjectMapper().findAndRegisterModules())

    @Test fun `스냅샷 왕복은 소유자와 원래 대화 및 마이크로초 단위 만료 시각에 묶인다`() {
        val user = UUID.randomUUID(); val turn = UUID.randomUUID()
        val prior = mutableListOf(AiConversationTurn("창세기 말씀", "말씀으로 위로", "마음은 어떤가요?"))
        val snapshot = AiContextSnapshot.capture(AiConversationContext(UUID.randomUUID(), prior), Instant.parse("2026-10-07T01:02:03.123456789Z"))
        prior += AiConversationTurn("다른 주제", "다음 답변", null)
        assertEquals(1, snapshot.context.turns.size)
        assertEquals(Instant.parse("2026-10-07T01:02:03.123456Z"), snapshot.capturedAt)
        assertEquals(snapshot.capturedAt.plus(30, ChronoUnit.DAYS), snapshot.expiresAt)
        val encrypted = cipher.encrypt(user, turn, snapshot)
        assertFalse(encrypted.contains("창세기"))
        assertEquals(snapshot, cipher.decrypt(user, turn, encrypted, snapshot.expiresAt))
        assertThrows(Exception::class.java) { cipher.decrypt(UUID.randomUUID(), turn, encrypted, snapshot.expiresAt) }
        assertThrows(Exception::class.java) { cipher.decrypt(user, UUID.randomUUID(), encrypted, snapshot.expiresAt) }
        assertThrows(Exception::class.java) { cipher.decrypt(user, turn, encrypted, snapshot.expiresAt.plusSeconds(1)) }
        assertThrows(Exception::class.java) { cipher.encrypt(user, turn, snapshot.copy(schemaVersion = 2)) }
        assertThrows(Exception::class.java) { cipher.encrypt(user, turn, snapshot.copy(expiresAt = snapshot.expiresAt.plusSeconds(1))) }
    }
}

/** 실제 usage/history/conversation에서 문맥 복원·삭제·완료 fencing과 실패 비용을 검증합니다. */
@SpringBootTest
@Import(AiHistoryTestConfiguration::class, AiContextSnapshotTests.SnapshotTestConfiguration::class)
@TestPropertySource(properties = ["app.bible.kor1910-html-path=src/test/resources/bible/kor_html", "app.bible.import-enabled=true",
    "app.ai.mock-enabled=true", "app.ai.rag-lexical-fallback-enabled=true", "app.ai.evaluation-enabled=false"])
class AiContextSnapshotTests(
    @Autowired private val users: UserRepository,
    @Autowired private val assistant: AskBibleAssistantUseCase,
    @Autowired private val feedback: AiAnswerFeedbackService,
    @Autowired private val history: AiAnswerHistoryService,
    @Autowired private val records: AiAnswerHistoryRepository,
    @Autowired private val snapshots: AiContextSnapshotPort,
    @Autowired private val snapshotCipher: AiContextSnapshotCipher,
    @Autowired private val conversations: AiConversationRepository,
    @Autowired private val usage: AiUsagePort,
    @Autowired private val usages: AiUsageLogRepository,
    @Autowired private val cleanup: AiAnswerHistoryCleanupService,
    @Autowired private val completion: ObservedCompletion,
    @Autowired private val scope: ObservedScope,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val auth: com.example.mypasture.auth.application.AuthService,
    @Autowired private val transactionManager: org.springframework.transaction.PlatformTransactionManager,
    @Autowired private val dataSource: javax.sql.DataSource,
    @Autowired private val runtimeConfig: AiRuntimeConfigService,
    @Autowired private val policy: AiAssistantExperiencePolicyResolver,
) {
    @BeforeEach fun reset() { completion.afterGeneration = null; completion.contexts.clear(); scope.contexts.clear() }
    private fun user() = users.save(UserEntity(email = "context-${UUID.randomUUID()}@example.com", displayName = "Context"))
    private fun setup(): Pair<UserEntity, GroundedAnswer> {
        val owner = user()
        val first = assistant.ask(owner.id, SubscriptionTier.PREMIUM, "창세기 말씀을 설명해주세요")
        val followup = assistant.ask(owner.id, SubscriptionTier.PREMIUM, "네", first.conversationId)
        feedback.submit(owner.id, followup.turnId, AiFeedbackRating.NOT_HELPFUL, setOf(AiFeedbackReason.TOO_GENERIC), null)
        return owner to followup
    }

    @Test fun `재생성은 이후 대화가 바뀌어도 이전 문맥을 정확히 사용하고 만료를 연장하지 않는다`() = runBlocking {
        val (owner, source) = setup()
        val saved = snapshots.restore(owner.id, source.turnId)!!
        assertEquals(1, saved.snapshot!!.context.turns.size)
        assertEquals("창세기 말씀을 설명해주세요", saved.snapshot!!.context.turns.single().userQuestion)
        val latest = assistant.ask(owner.id, SubscriptionTier.PREMIUM, "시편 1장 1절을 설명해주세요", source.conversationId)
        assertNotNull(latest.conversationId)
        val count = conversations.findById(source.conversationId!!).orElseThrow().turnCount
        val regenerated = feedback.regenerate(owner.id, SubscriptionTier.PREMIUM, source.turnId, "네")
        assertEquals(saved.snapshot!!.context, scope.contexts.last())
        assertEquals(saved.snapshot!!.context, completion.contexts.last())
        assertTrue(regenerated.citations.any { it.book == "창세기" })
        assertEquals(count, conversations.findById(source.conversationId!!).orElseThrow().turnCount)
        val newSnapshot = snapshots.restore(owner.id, regenerated.turnId)!!
        assertEquals(saved.snapshot, newSnapshot.snapshot)
        assertEquals(saved.sourceExpiresAt, newSnapshot.sourceExpiresAt)
        assertEquals(BigDecimal("0.0011"), regenerated.estimatedCostUsd)
        assertEquals(11, regenerated.evaluationPromptTokens)
        assertEquals(3, usage.countToday(owner.id))
        val serialized = jacksonObjectMapper().findAndRegisterModules().writeValueAsString(regenerated)
        assertFalse(serialized.contains("snapshot")); assertFalse(serialized.contains(saved.sourceCiphertext!!))
    }

    @Test fun `첫 질문에는 이전 스냅샷이 없고 새 후속 질문에는 필요하며 기존 데이터는 안전하게 실패한다`() = runBlocking {
        val owner = user(); val first = assistant.ask(owner.id, SubscriptionTier.PREMIUM, "창세기 말씀을 설명해주세요")
        assertNull(records.findById(first.turnId).orElseThrow().contextSnapshotCiphertext)
        val followup = assistant.ask(owner.id, SubscriptionTier.PREMIUM, "네", first.conversationId)
        assertNotNull(records.findById(followup.turnId).orElseThrow().contextSnapshotCiphertext)
        jdbc.update("update ai_answer_history set context_snapshot_ciphertext = null, context_snapshot_expires_at = null where turn_id = ?", followup.turnId)
        feedback.submit(owner.id, followup.turnId, AiFeedbackRating.NOT_HELPFUL, setOf(AiFeedbackReason.TOO_GENERIC), null)
        assertThrows(AiQuestionScopeUnclearException::class.java) { runBlocking { feedback.regenerate(owner.id, SubscriptionTier.PREMIUM, followup.turnId, "네") } }
        assertNotNull(usage.findCompletedTurn(owner.id, followup.turnId))
        feedback.submit(owner.id, first.turnId, AiFeedbackRating.NOT_HELPFUL, setOf(AiFeedbackReason.TOO_GENERIC), null)
        assertNotNull(feedback.regenerate(owner.id, SubscriptionTier.PREMIUM, first.turnId, "창세기 말씀을 설명해주세요"))
    }

    @Test fun `만료된 스냅샷은 정리 전에도 사용할 수 없고 암호문은 지우되 기록은 남긴다`() = runBlocking {
        val (owner, source) = setup(); val saved = snapshots.restore(owner.id, source.turnId)!!
        val expired = saved.snapshot!!.copy(capturedAt = Instant.now().minus(31, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS),
            expiresAt = Instant.now().minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS))
        val exactExpired = expired.copy(expiresAt = expired.capturedAt.plus(30, ChronoUnit.DAYS))
        jdbc.update("update ai_answer_history set context_snapshot_ciphertext = ?, context_snapshot_expires_at = ? where turn_id = ?",
            snapshotCipher.encrypt(owner.id, source.turnId, exactExpired), java.sql.Timestamp.from(exactExpired.expiresAt), source.turnId)
        assertNull(snapshots.restore(owner.id, source.turnId)!!.snapshot)
        assertThrows(AiQuestionScopeUnclearException::class.java) { runBlocking { feedback.regenerate(owner.id, SubscriptionTier.PREMIUM, source.turnId, "네") } }
        assertTrue(cleanup.clearExpiredSnapshots() >= 1)
        assertNull(records.findById(source.turnId).orElseThrow().contextSnapshotCiphertext)
        assertTrue(history.detail(owner.id, source.turnId).versions.isNotEmpty())
    }

    @Test fun `같은 소유자의 다른 대화로 암호문을 옮기면 원본 AAD 검사에서 실패한다`() {
        val (owner, source) = setup(); val other = assistant.ask(owner.id, SubscriptionTier.PREMIUM, "창세기 2장 1절을 설명해주세요", source.conversationId)
        val original = records.findById(source.turnId).orElseThrow()
        jdbc.update("update ai_answer_history set context_snapshot_ciphertext = ?, context_snapshot_expires_at = ? where turn_id = ?",
            original.contextSnapshotCiphertext, java.sql.Timestamp.from(original.contextSnapshotExpiresAt), other.turnId)
        assertThrows(AiQuestionScopeUnclearException::class.java) { snapshots.restore(owner.id, other.turnId) }
        assertNull(snapshots.restore(user().id, source.turnId))
    }

    @Test fun `소유한 기록을 하나라도 삭제하면 모든 스냅샷과 대화를 지우고 다른 답변은 보존한다`() {
        val (owner, source) = setup(); val other = assistant.ask(owner.id, SubscriptionTier.PREMIUM, "시편 1장 1절 말씀", source.conversationId)
        val stranger = user()
        history.delete(stranger.id, source.turnId)
        assertNotNull(snapshots.restore(owner.id, source.turnId))
        history.delete(owner.id, other.turnId)
        assertNull(records.findById(source.turnId).orElseThrow().contextSnapshotCiphertext)
        assertFalse(conversations.existsById(source.conversationId!!))
        assertTrue(history.detail(owner.id, source.turnId).versions.isNotEmpty())
        assertThrows(AiQuestionScopeUnclearException::class.java) { runBlocking { feedback.regenerate(owner.id, SubscriptionTier.PREMIUM, source.turnId, "네") } }
    }

    @Test fun `생성 뒤 삭제가 확정되면 오래된 재생성 기록을 막고 이미 소비한 비용을 기록한다`() = runBlocking {
        val (owner, source) = setup()
        completion.afterGeneration = { history.delete(owner.id, source.turnId) }
        assertThrows(AiQuestionScopeUnclearException::class.java) { runBlocking { feedback.regenerate(owner.id, SubscriptionTier.PREMIUM, source.turnId, "네") } }
        val failed = usages.findAllByUserId(owner.id).single { it.parentTurnId == source.turnId }
        assertEquals(AiUsageStatus.FAILED, failed.status)
        assertEquals("AI_QUESTION_SCOPE_UNCLEAR", failed.errorCode)
        assertEquals(BigDecimal("0.001100"), failed.estimatedCostUsd)
        assertEquals(11, failed.evaluationPromptTokens)
        assertFalse(records.existsById(failed.id)); assertFalse(conversations.existsById(source.conversationId!!))
    }

    @Test fun `질문 생성 뒤 삭제가 확정되면 오래된 문맥 추가와 새 스냅샷을 막는다`() {
        val (owner, source) = setup()
        completion.afterGeneration = { history.delete(owner.id, source.turnId) }
        assertThrows(AiQuestionScopeUnclearException::class.java) { assistant.ask(owner.id, SubscriptionTier.PREMIUM, "창세기 2장 1절을 설명해주세요", source.conversationId) }
        val failed = usages.findAllByUserId(owner.id).single { it.status == AiUsageStatus.FAILED }
        assertEquals(BigDecimal("0.001100"), failed.estimatedCostUsd)
        assertFalse(records.existsById(failed.id)); assertFalse(conversations.existsById(source.conversationId!!))
    }

    @Test fun `원본 암호문만 무효화해도 읽어 둔 재생성을 막고 기존 답변은 유지한다`() = runBlocking {
        val (owner, source) = setup()
        completion.afterGeneration = { jdbc.update("update ai_answer_history set context_snapshot_ciphertext = null, context_snapshot_expires_at = null where turn_id = ?", source.turnId) }
        assertThrows(AiQuestionScopeUnclearException::class.java) { runBlocking { feedback.regenerate(owner.id, SubscriptionTier.PREMIUM, source.turnId, "네") } }
        assertEquals(1, history.detail(owner.id, source.turnId).versions.size)
    }

    @Test fun `닫힌 대화는 과거 복원을 허용하지만 계정 삭제는 스냅샷을 지우고 복구를 막는다`() {
        val (owner, source) = setup()
        jdbc.update("update ai_conversations set status = 'CLOSED' where id = ?", source.conversationId)
        assertNotNull(snapshots.restore(owner.id, source.turnId))
        auth.deleteAccount(owner.id, Instant.now(), com.example.mypasture.auth.presentation.dto.DeleteAccountRequest("DELETE", null))
        assertFalse(records.existsById(source.turnId)); assertFalse(conversations.existsById(source.conversationId!!))
        assertNull(snapshots.restore(owner.id, source.turnId))
    }

    @Test fun `기존 및 만료 문맥의 재생성도 생성 후 원본 삭제를 막지 못한다`() {
        for (expiredContext in listOf(false, true)) {
            val owner = user(); val question = "창세기 1장 1절을 설명해주세요"
            val first = assistant.ask(owner.id, SubscriptionTier.PREMIUM, question)
            val source = if (expiredContext) assistant.ask(owner.id, SubscriptionTier.PREMIUM, question, first.conversationId) else first
            if (expiredContext) {
                val saved = snapshots.restore(owner.id, source.turnId)!!
                val capturedAt = Instant.now().minus(31, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS)
                val expired = saved.snapshot!!.copy(capturedAt = capturedAt, expiresAt = capturedAt.plus(30, ChronoUnit.DAYS))
                jdbc.update("update ai_answer_history set context_snapshot_ciphertext = ?, context_snapshot_expires_at = ? where turn_id = ?",
                    snapshotCipher.encrypt(owner.id, source.turnId, expired), java.sql.Timestamp.from(expired.expiresAt), source.turnId)
            }
            assertNotNull(snapshots.restore(owner.id, source.turnId))
            assertNull(snapshots.restore(owner.id, source.turnId)!!.snapshot)
            feedback.submit(owner.id, source.turnId, AiFeedbackRating.NOT_HELPFUL, setOf(AiFeedbackReason.TOO_GENERIC), null)
            completion.afterGeneration = { history.delete(owner.id, source.turnId) }
            assertThrows(AiQuestionScopeUnclearException::class.java) {
                runBlocking { feedback.regenerate(owner.id, SubscriptionTier.PREMIUM, source.turnId, question) }
            }
            val failed = usages.findAllByUserId(owner.id).single { it.parentTurnId == source.turnId }
            assertEquals(AiUsageStatus.FAILED, failed.status); assertFalse(records.existsById(failed.id))
            assertEquals(BigDecimal("0.001100"), failed.estimatedCostUsd)
        }
    }

    @Test fun `읽어 둔 뒤 원본의 기록 묶음이 바뀌면 문맥이 없어도 완료할 수 없다`() {
        val owner = user(); val question = "창세기 1장 1절을 설명해주세요"
        val source = assistant.ask(owner.id, SubscriptionTier.FREE, question)
        val other = assistant.ask(owner.id, SubscriptionTier.FREE, "창세기 2장 1절을 설명해주세요")
        feedback.submit(owner.id, source.turnId, AiFeedbackRating.NOT_HELPFUL, setOf(AiFeedbackReason.TOO_GENERIC), null)
        completion.afterGeneration = { jdbc.update("update ai_answer_history set parent_turn_id = ? where turn_id = ?", other.turnId, source.turnId) }
        assertThrows(AiQuestionScopeUnclearException::class.java) {
            runBlocking { feedback.regenerate(owner.id, SubscriptionTier.FREE, source.turnId, question) }
        }
        val failed = usages.findAllByUserId(owner.id).single { it.parentTurnId == source.turnId }
        assertFalse(records.existsById(failed.id)); assertEquals(AiUsageStatus.FAILED, failed.status)
    }

    @Test fun `스냅샷 저장에 실패하면 대화 추가와 기록 영수증 완료를 함께 롤백한다`() {
        val (owner, source) = setup(); val restored = snapshots.restore(owner.id, source.turnId)!!
        val originalConversation = conversations.findById(source.conversationId!!).orElseThrow()
        val requestId = UUID.randomUUID()
        val reservation = usage.reserve(owner.id, "창세기 말씀", 100, clientRequestId = requestId) as AiUsageReservation.Started
        val failure = assertThrows(org.springframework.dao.InvalidDataAccessApiUsageException::class.java) {
            usage.completeWithContext(reservation.turnId, source.copy(turnId = reservation.turnId), "창세기 말씀", 1,
                AiTurnCompletionContext(snapshot = restored.snapshot!!.copy(schemaVersion = 2),
                    appendPolicy = policy.resolve(SubscriptionTier.PREMIUM)))
        }
        assertTrue(failure.cause is IllegalArgumentException)
        assertEquals(originalConversation.turnCount, conversations.findById(source.conversationId!!).orElseThrow().turnCount)
        assertEquals(originalConversation.encryptedContext, conversations.findById(source.conversationId!!).orElseThrow().encryptedContext)
        assertEquals(AiUsageStatus.PENDING, usages.findById(reservation.turnId).orElseThrow().status)
        assertEquals(AiClientRequestStatus.PROCESSING, usage.findClientRequest(owner.id, requestId)!!.status)
        assertFalse(records.existsById(reservation.turnId))
        usage.fail(reservation.turnId, "TEST_SNAPSHOT_WRITE_FAILURE")
    }

    @Test fun `PostgreSQL에서 사용자 잠금을 잡은 삭제는 읽어 둔 스냅샷 완료를 확정 뒤 차단한다`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(dataSource.connection.use { it.metaData.databaseProductName == "PostgreSQL" },
            "Requires PostgreSQL row-lock inspection")
        listOf(false, true).forEach(::assertPostgresDeleteFence)
    }

    private fun assertPostgresDeleteFence(hasSnapshot: Boolean) {
        val (owner, source) = if (hasSnapshot) setup() else {
            val owner = user()
            owner to assistant.ask(owner.id, SubscriptionTier.PREMIUM, "창세기 말씀을 설명해주세요")
        }
        val question = if (hasSnapshot) "네" else "창세기 말씀을 설명해주세요"
        val restored = snapshots.restore(owner.id, source.turnId)!!
        assertEquals(hasSnapshot, restored.snapshot != null)
        val reservationId = usage.reserveRegeneration(owner.id, question, source.turnId, runtimeConfig.current())
        val deleted = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        val applicationName = "snapshot-complete-${UUID.randomUUID()}"
        try {
            val deletion = executor.submit {
                org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult {
                    history.delete(owner.id, source.turnId)
                    deleted.countDown(); check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                }
            }
            assertTrue(deleted.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val completing = executor.submit {
                org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult {
                    jdbc.queryForObject("select set_config('application_name', ?, true)", String::class.java, applicationName)
                    usage.completeWithContext(reservationId, source.copy(turnId = reservationId), question, 1,
                        AiTurnCompletionContext(restored.snapshot, restored))
                }
            }
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            var waiting = false
            while (System.nanoTime() < deadline) {
                waiting = (jdbc.queryForObject("select count(*) from pg_stat_activity where application_name = ? and wait_event_type = 'Lock'",
                    Int::class.java, applicationName) ?: 0) > 0
                if (waiting) break
                Thread.sleep(10)
            }
            assertTrue(waiting, "Completion must wait on the owner's durable deletion lock")
            release.countDown(); deletion.get(15, java.util.concurrent.TimeUnit.SECONDS)
            val failure = assertThrows(java.util.concurrent.ExecutionException::class.java) { completing.get(15, java.util.concurrent.TimeUnit.SECONDS) }
            assertTrue(failure.cause is AiQuestionScopeUnclearException)
            assertFalse(records.existsById(reservationId)); assertFalse(conversations.existsById(source.conversationId!!))
            assertNull(snapshots.restore(owner.id, source.turnId))
            usage.fail(reservationId, "AI_QUESTION_SCOPE_UNCLEAR", source.copy(turnId = reservationId))
            assertEquals(AiUsageStatus.FAILED, usages.findById(reservationId).orElseThrow().status)
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @TestConfiguration class SnapshotTestConfiguration {
        @Bean @Primary fun observedCompletion() = ObservedCompletion()
        @Bean @Primary fun observedScope() = ObservedScope()
    }
    /** 실제 모델 대신 당시 generation 입력과 완료 직전 삭제 경계를 관측합니다. */
    class ObservedCompletion : AiCompletionPort {
        val contexts = mutableListOf<AiConversationContext?>(); var afterGeneration: (() -> Unit)? = null
        override suspend fun answerWithCitations(question: String, citations: List<BibleCitation>, config: AiRuntimeConfigSnapshot,
            experiencePolicy: AiAssistantExperiencePolicy, conversationContext: AiConversationContext?, generationInstructions: List<String>,
            previousAnswer: String?, revisionInstructions: List<String>): GroundedAnswer {
            contexts += conversationContext
            val answer = LocalAiCompletionAdapter().answerWithCitations(question, citations, config, experiencePolicy, conversationContext)
                .copy(estimatedCostUsd = BigDecimal("0.001"))
            val action = afterGeneration; afterGeneration = null; action?.invoke()
            return answer
        }
    }
    /** scope 입력 관측과 평가 비용의 실패 영속 기록을 함께 검증합니다. */
    class ObservedScope : AiQuestionScopePort {
        val contexts = mutableListOf<AiConversationContext?>()
        override suspend fun assess(currentQuestion: String, conversationContext: AiConversationContext?, config: AiRuntimeConfigSnapshot): AiQuestionScopeAssessment {
            contexts += conversationContext
            return LocalAiCompletionAdapter().assess(currentQuestion, conversationContext, config)
                .copy(promptTokens = 11, completionTokens = 3, estimatedCostUsd = BigDecimal("0.0001"))
        }
    }
    companion object {
        @JvmStatic @DynamicPropertySource fun postgres(registry: DynamicPropertyRegistry) = AiHistoryTestConfiguration.postgres(registry)
    }
}
