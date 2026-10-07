package com.example.mypasture.ai

import com.example.mypasture.ai.application.AiAnswerHistoryService
import com.example.mypasture.ai.application.AiHistoryInvalidCursorException
import com.example.mypasture.ai.application.AiHistoryNotFoundException
import com.example.mypasture.ai.application.AskBibleAssistantUseCase
import com.example.mypasture.ai.application.AiAnswerFeedbackService
import com.example.mypasture.ai.application.AiUsagePort
import com.example.mypasture.ai.domain.AiUsageReservation
import com.example.mypasture.ai.domain.AiUsageStatus
import com.example.mypasture.ai.domain.AiClientRequestStatus
import com.example.mypasture.ai.domain.GroundedAnswer
import com.example.mypasture.ai.infrastructure.AiUsageLogRepository
import com.example.mypasture.ai.domain.AI_ASSISTANT_RESPONSE_SCHEMA_VERSION
import com.example.mypasture.ai.domain.AI_PASTOR_VERSION
import com.example.mypasture.ai.domain.AiAnswerHistoryEntity
import com.example.mypasture.ai.domain.AiFeedbackRating
import com.example.mypasture.ai.domain.AiFeedbackReason
import com.example.mypasture.ai.infrastructure.AiAnswerHistoryCipher
import com.example.mypasture.ai.infrastructure.AiAnswerHistoryCleanupService
import com.example.mypasture.ai.infrastructure.AiAnswerHistoryRepository
import com.example.mypasture.auth.infrastructure.UserRepository
import com.example.mypasture.user.domain.SubscriptionTier
import com.example.mypasture.user.domain.UserEntity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.springframework.jdbc.core.JdbcTemplate

/** 실제 AI 완료 흐름과 동일한 DB에서 기록 암호화·소유권·삭제·만료를 검증합니다. */
@SpringBootTest
@Import(AiHistoryTestConfiguration::class)
@TestPropertySource(
    properties = [
        "app.bible.kor1910-html-path=src/test/resources/bible/kor_html",
        "app.bible.import-enabled=true",
        "app.ai.mock-enabled=true",
        "app.ai.rag-lexical-fallback-enabled=true",
    ],
)
class AiAnswerHistoryTests(
    @Autowired private val users: UserRepository,
    @Autowired private val assistant: AskBibleAssistantUseCase,
    @Autowired private val feedback: AiAnswerFeedbackService,
    @Autowired private val history: AiAnswerHistoryService,
    @Autowired private val records: AiAnswerHistoryRepository,
    @Autowired private val cipher: AiAnswerHistoryCipher,
    @Autowired private val cleanup: AiAnswerHistoryCleanupService,
    @Autowired private val usage: AiUsagePort,
    @Autowired private val snapshots: com.example.mypasture.ai.application.AiContextSnapshotPort,
    @Autowired private val usageRecords: AiUsageLogRepository,
    @Autowired private val transactionManager: PlatformTransactionManager,
    @Autowired private val runtimeConfig: com.example.mypasture.ai.application.AiRuntimeConfigService,
    @Autowired private val auth: com.example.mypasture.auth.application.AuthService,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val receiptMaintenance: com.example.mypasture.ai.infrastructure.AiRequestReceiptMaintenanceService,
    @Autowired private val receipts: com.example.mypasture.ai.infrastructure.AiRequestReceiptRepository,
) {
    @Test
    fun `completed answer stores encrypted question prayer citations and independent turn ids`() {
        val user = user()
        val question = "창세기 1장 1절 설명 ${UUID.randomUUID()}"
        val requestId = UUID.randomUUID()
        val first = assistant.ask(user.id, SubscriptionTier.FREE, question, clientRequestId = requestId)
        assertEquals(first, assistant.ask(user.id, SubscriptionTier.FREE, question, clientRequestId = requestId))
        val cached = assistant.ask(user.id, SubscriptionTier.FREE, question)

        assertTrue(cached.cacheHit)
        assertFalse(first.turnId == cached.turnId)
        val page = history.list(user.id, 1, null)
        assertEquals(1, page.items.size)
        assertTrue(page.nextCursor != null)
        assertEquals(1, history.list(user.id, 1, page.nextCursor).items.size)
        assertEquals(2, history.list(user.id, 20, null).items.size)
        val row = records.findById(first.turnId).orElseThrow()
        assertFalse(row.questionCiphertext.contains(question))
        assertFalse(row.responseCiphertext.contains(first.answer))
        assertEquals(AI_PASTOR_VERSION, row.assistantVersion)
        val detail = history.detail(user.id, first.turnId)
        assertEquals(question, detail.question)
        assertEquals(first.answer, detail.versions.single().answer)
        assertEquals(first.citations, detail.versions.single().citations)
        assertEquals(first.prayer, detail.versions.single().prayer)
        assertEquals("ORIGINAL", detail.versions.single().kind)
        assertEquals(AI_ASSISTANT_RESPONSE_SCHEMA_VERSION, detail.versions.single().responseSchemaVersion)
    }

    @Test
    fun `regeneration preserves original and deletion through child removes whole group`() = runTest {
        val owner = user()
        val stranger = user()
        val question = "창세기 1장 1절을 설명해주세요 ${UUID.randomUUID()}"
        val original = assistant.ask(owner.id, SubscriptionTier.FREE, question)
        feedback.submit(owner.id, original.turnId, AiFeedbackRating.NOT_HELPFUL, setOf(AiFeedbackReason.TOO_GENERIC), null)
        val newer = feedback.regenerate(owner.id, SubscriptionTier.FREE, original.turnId, question)

        val detail = history.detail(owner.id, newer.turnId)
        assertEquals(original.turnId, detail.rootTurnId)
        assertEquals(listOf("ORIGINAL", "REGENERATED"), detail.versions.map { it.kind })
        assertEquals(listOf(original.turnId, newer.turnId), detail.versions.map { it.turnId })
        assertEquals(1L, history.list(owner.id, 20, null).items.single().revisionCount)
        assertThrows(AiHistoryNotFoundException::class.java) { history.detail(stranger.id, newer.turnId) }
        assertEquals(0, history.list(stranger.id, 20, null).items.size)
        history.delete(stranger.id, newer.turnId)
        assertEquals(2, history.detail(owner.id, original.turnId).versions.size)
        history.delete(owner.id, newer.turnId)
        history.delete(owner.id, newer.turnId)
        assertEquals(0, history.list(owner.id, 20, null).items.size)
        assertFalse(records.existsById(original.turnId))
        assertFalse(records.existsById(newer.turnId))
    }

    @Test
    fun `cursor rejects tampering and use by another user`() {
        val owner = user()
        val stranger = user()
        repeat(2) { assistant.ask(owner.id, SubscriptionTier.FREE, "창세기 1장 1절 설명 ${UUID.randomUUID()}") }
        val cursor = history.list(owner.id, 1, null).nextCursor!!
        assertThrows(AiHistoryInvalidCursorException::class.java) { history.list(stranger.id, 1, cursor) }
        assertThrows(AiHistoryInvalidCursorException::class.java) { history.list(owner.id, 1, cursor + "bad") }
        assertThrows(IllegalArgumentException::class.java) { history.list(owner.id, 0, null) }
    }

    @Test
    fun `cleanup expires old root together with newer regenerated answer`() {
        val user = user()
        val response = assistant.ask(user.id, SubscriptionTier.FREE, "창세기 1장 1절 설명 ${UUID.randomUUID()}")
        val oldId = UUID.randomUUID()
        val newerId = UUID.randomUUID()
        val common = AiAnswerHistoryEntity(
            turnId = oldId,
            userId = user.id,
            parentTurnId = null,
            conversationId = null,
            questionCiphertext = cipher.encrypt("오래된 질문"),
            responseCiphertext = cipher.encrypt(response.copy(turnId = oldId)),
            modelConfigVersionId = null,
            modelVersionNumber = null,
            generatorModel = "local-test",
            assistantVersion = AI_PASTOR_VERSION,
            responseSchemaVersion = AI_ASSISTANT_RESPONSE_SCHEMA_VERSION,
            createdAt = Instant.now().minus(366, ChronoUnit.DAYS),
        )
        records.save(common)
        records.save(
            AiAnswerHistoryEntity(
                turnId = newerId,
                userId = user.id,
                parentTurnId = oldId,
                conversationId = null,
                questionCiphertext = cipher.encrypt("오래된 질문"),
                responseCiphertext = cipher.encrypt(response.copy(turnId = newerId)),
                modelConfigVersionId = null,
                modelVersionNumber = null,
                generatorModel = "local-test",
                assistantVersion = AI_PASTOR_VERSION,
                responseSchemaVersion = AI_ASSISTANT_RESPONSE_SCHEMA_VERSION,
                createdAt = Instant.now(),
            ),
        )

        assertFalse(history.list(user.id, 20, null).items.any { it.turnId == oldId })
        assertThrows(AiHistoryNotFoundException::class.java) { history.detail(user.id, newerId) }
        assertEquals(1, cleanup.deleteExpired())
        assertFalse(records.existsById(oldId))
        assertFalse(records.existsById(newerId))
        assertTrue(records.existsById(response.turnId))
    }

    @Test
    fun `regenerating a revision stays in original group and deleted history never reappears`() = runTest {
        val owner = user()
        val question = "창세기 1장 1절 설명 ${UUID.randomUUID()}"
        val original = assistant.ask(owner.id, SubscriptionTier.FREE, question)
        feedback.submit(owner.id, original.turnId, AiFeedbackRating.NOT_HELPFUL, setOf(AiFeedbackReason.TOO_GENERIC), null)
        val second = feedback.regenerate(owner.id, SubscriptionTier.FREE, original.turnId, question)
        feedback.submit(owner.id, second.turnId, AiFeedbackRating.NOT_HELPFUL, setOf(AiFeedbackReason.TOO_GENERIC), null)
        val third = feedback.regenerate(owner.id, SubscriptionTier.FREE, second.turnId, question)
        assertEquals(original.turnId, records.findById(third.turnId).orElseThrow().parentTurnId)
        assertEquals(second.turnId, usageRecords.findById(third.turnId).orElseThrow().parentTurnId)
        assertEquals(3, history.detail(owner.id, second.turnId).versions.size)
        assertEquals(2L, history.list(owner.id, 20, null).items.single().revisionCount)
        history.delete(owner.id, second.turnId)
        assertFalse(records.existsById(third.turnId))
        feedback.submit(owner.id, third.turnId, AiFeedbackRating.NOT_HELPFUL, setOf(AiFeedbackReason.TOO_GENERIC), null)
        assertThrows(com.example.mypasture.ai.application.AiQuestionScopeUnclearException::class.java) {
            runBlocking { feedback.regenerate(owner.id, SubscriptionTier.FREE, third.turnId, question) }
        }
        val afterDeletion = usageRecords.findAllByUserId(owner.id).single { it.parentTurnId == third.turnId }
        assertFalse(records.existsById(afterDeletion.id))
        assertEquals(AiUsageStatus.FAILED, afterDeletion.status)
        assertTrue(history.list(owner.id, 20, null).items.isEmpty())
    }

    @Test
    fun `usage receipt and history roll back together and repeated completion cannot restore deletion`() {
        val owner = user()
        val requestId = UUID.randomUUID()
        val reservation = usage.reserve(owner.id, "원자적 완료", 10, clientRequestId = requestId) as AiUsageReservation.Started
        val answer = testAnswer(reservation.turnId)
        assertThrows(IllegalStateException::class.java) {
            TransactionTemplate(transactionManager).executeWithoutResult {
                usage.complete(reservation.turnId, answer, "원자적 완료", 1)
                records.flush()
                throw IllegalStateException("Synthetic transaction failure")
            }
        }
        assertEquals(AiUsageStatus.PENDING, usageRecords.findById(reservation.turnId).orElseThrow().status)
        assertEquals(AiClientRequestStatus.PROCESSING, usage.findClientRequest(owner.id, requestId)!!.status)
        assertFalse(records.existsById(reservation.turnId))

        val executor = Executors.newFixedThreadPool(2)
        try {
            listOf(answer, answer.copy(answer = "경합한 다른 답변")).map { competing ->
                executor.submit { usage.complete(reservation.turnId, competing, "원자적 완료", 1) }
            }.forEach { it.get(20, TimeUnit.SECONDS) }
        } finally { executor.shutdownNow() }
        assertEquals(1, history.list(owner.id, 20, null).items.size)
        val completedRequest = usage.findClientRequest(owner.id, requestId)!!
        assertEquals(AiClientRequestStatus.COMPLETED, completedRequest.status)
        assertEquals(completedRequest.answer!!.answer, history.detail(owner.id, reservation.turnId).versions.single().answer)
        usage.complete(reservation.turnId, answer.copy(answer = "나중 완료"), "원자적 완료", 1)
        assertEquals(completedRequest.answer, usage.findClientRequest(owner.id, requestId)!!.answer)
        val completedUsage = usageRecords.findById(reservation.turnId).orElseThrow()
        usage.fail(reservation.turnId, "LATE_FAILURE", answer.copy(estimatedCostUsd = BigDecimal.ONE))
        assertEquals(AiUsageStatus.COMPLETED, usageRecords.findById(reservation.turnId).orElseThrow().status)
        assertEquals(completedUsage.estimatedCostUsd, usageRecords.findById(reservation.turnId).orElseThrow().estimatedCostUsd)
        assertEquals(completedRequest.answer, usage.findClientRequest(owner.id, requestId)!!.answer)
        assertEquals(completedRequest.answer!!.answer, history.detail(owner.id, reservation.turnId).versions.single().answer)
        history.delete(owner.id, reservation.turnId)
        usage.complete(reservation.turnId, answer, "원자적 완료", 1)
        assertFalse(records.existsById(reservation.turnId))
    }

    @Test
    fun `deletion committing during regeneration completion removes the entire group`() {
        val owner = user()
        val question = "창세기 설명 ${UUID.randomUUID()}"
        val original = assistant.ask(owner.id, SubscriptionTier.FREE, question)
        val config = runtimeConfig.current()
        val revisionId = usage.reserveRegeneration(owner.id, question, original.turnId, config)
        val sourceFence = snapshots.restore(owner.id, original.turnId)!!
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val deletion = executor.submit {
                TransactionTemplate(transactionManager).executeWithoutResult {
                    users.findActiveByIdForUpdate(owner.id)!!
                    records.lockByTurnIdAndUserId(original.turnId, owner.id)!!
                    locked.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    history.delete(owner.id, original.turnId)
                }
            }
            assertTrue(locked.await(10, TimeUnit.SECONDS))
            val completion = executor.submit {
                usage.completeWithContext(revisionId, testAnswer(revisionId), question, 1,
                    com.example.mypasture.ai.domain.AiTurnCompletionContext(sourceFence.snapshot, sourceFence))
            }
            release.countDown()
            deletion.get(20, TimeUnit.SECONDS)
            val failure = assertThrows(java.util.concurrent.ExecutionException::class.java) { completion.get(20, TimeUnit.SECONDS) }
            assertTrue(failure.cause is com.example.mypasture.ai.application.AiQuestionScopeUnclearException)
            usage.fail(revisionId, "AI_QUESTION_SCOPE_UNCLEAR")
            assertTrue(history.list(owner.id, 20, null).items.isEmpty())
            assertFalse(records.existsById(revisionId))
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test
    fun `equal timestamp cursor visits each UUID exactly once across sign boundary`() {
        val owner = user()
        val timestamp = Instant.parse("2026-09-30T00:00:00.123456Z")
        val ids = listOf("00000000-0000-0000-0000-000000000001", "7fffffff-ffff-ffff-ffff-ffffffffffff", "80000000-0000-0000-0000-000000000000", "ffffffff-ffff-ffff-ffff-ffffffffffff").map(UUID::fromString)
        ids.forEach { id ->
            records.save(AiAnswerHistoryEntity(id, owner.id, null, null, cipher.encrypt("동일 시각 질문"),
                cipher.encrypt(testAnswer(id)), null, null, "test", AI_PASTOR_VERSION,
                AI_ASSISTANT_RESPONSE_SCHEMA_VERSION, timestamp))
        }
        var cursor: String? = null
        val visited = mutableListOf<UUID>()
        do {
            val page = history.list(owner.id, 1, cursor)
            visited += page.items.map { it.turnId }
            cursor = page.nextCursor
        } while (cursor != null)
        assertEquals(4, visited.size)
        assertEquals(ids.toSet(), visited.toSet())
    }

    @Test
    fun `postgres completion waits for account deletion without holding usage lock or restoring history`() {
        assumeTrue(!System.getenv("AI_HISTORY_TEST_JDBC_URL").isNullOrBlank(), "Requires PostgreSQL row-lock inspection")
        val owner = user()
        val reservation = usage.reserve(owner.id, "탈퇴 중 완료", 10) as AiUsageReservation.Started
        val executor = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        val completionPid = AtomicInteger()
        var completion: java.util.concurrent.Future<Throwable?>? = null
        try {
            TransactionTemplate(transactionManager).executeWithoutResult {
                users.findActiveByIdForUpdate(owner.id)!!
                completion = executor.submit<Throwable?> {
                    runCatching {
                        TransactionTemplate(transactionManager).executeWithoutResult {
                            completionPid.set(jdbc.queryForObject("select pg_backend_pid()", Int::class.java)!!)
                            started.countDown()
                            usage.complete(reservation.turnId, testAnswer(reservation.turnId), "탈퇴 중 완료", 1)
                        }
                    }.exceptionOrNull()
                }
                assertTrue(started.await(10, TimeUnit.SECONDS))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                var blocked = false
                while (System.nanoTime() < deadline) {
                    blocked = jdbc.queryForObject(
                        "select pg_backend_pid() = any(pg_blocking_pids(?))", Boolean::class.java, completionPid.get(),
                    )!!
                    if (blocked) break
                    Thread.sleep(25)
                }
                assertTrue(blocked, "Completion must wait for the deletion transaction's user lock")
                // 이전 usage → FK(user) 순서라면 이 조회가 실패해 교착 위험을 드러냅니다.
                jdbc.queryForObject("select id from ai_usage_logs where id = ? for update nowait", UUID::class.java, reservation.turnId)
                auth.deleteAccount(owner.id, Instant.now(), com.example.mypasture.auth.presentation.dto.DeleteAccountRequest("DELETE", null))
            }
            val failure = completion!!.get(20, TimeUnit.SECONDS)
            assertTrue(failure != null)
            assertTrue(generateSequence(failure) { it.cause }.any { it is IllegalStateException },
                "Expected account-removal failure, got ${failure?.javaClass?.simpleName}")
            assertFalse(usageRecords.existsById(reservation.turnId))
            assertTrue(history.list(owner.id, 20, null).items.isEmpty())
        } finally { executor.shutdownNow() }
    }

    @Test
    fun `postgres stale worker waiting on user cannot overwrite completion or deadlock account deletion`() {
        assumeTrue(!System.getenv("AI_HISTORY_TEST_JDBC_URL").isNullOrBlank(), "Requires PostgreSQL row-lock inspection")
        for (withdraw in listOf(false, true)) {
            val owner = user()
            val requestId = UUID.randomUUID()
            val reservation = usage.reserve(owner.id, "worker 경합", 10, clientRequestId = requestId) as AiUsageReservation.Started
            val receipt = receipts.findByUserIdAndClientRequestId(owner.id, requestId)!!
            receipt.updatedAt = Instant.now().minusSeconds(3600)
            receipts.saveAndFlush(receipt)
            val executor = Executors.newSingleThreadExecutor()
            val started = CountDownLatch(1)
            val workerPid = AtomicInteger()
            var worker: java.util.concurrent.Future<*>? = null
            try {
                TransactionTemplate(transactionManager).executeWithoutResult {
                    users.findActiveByIdForUpdate(owner.id)!!
                    worker = executor.submit {
                        TransactionTemplate(transactionManager).executeWithoutResult {
                            workerPid.set(jdbc.queryForObject("select pg_backend_pid()", Int::class.java)!!)
                            started.countDown()
                            receiptMaintenance.failStalePendingRequests()
                        }
                    }
                    assertTrue(started.await(10, TimeUnit.SECONDS))
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    var blocked = false
                    while (System.nanoTime() < deadline) {
                        blocked = jdbc.queryForObject("select pg_backend_pid() = any(pg_blocking_pids(?))", Boolean::class.java, workerPid.get())!!
                        if (blocked) break
                        Thread.sleep(25)
                    }
                    assertTrue(blocked, "Stale worker must acquire user before usage/receipt locks")
                    if (withdraw) {
                        auth.deleteAccount(owner.id, Instant.now(), com.example.mypasture.auth.presentation.dto.DeleteAccountRequest("DELETE", null))
                    } else {
                        usage.complete(reservation.turnId, testAnswer(reservation.turnId), "worker 경합", 1)
                    }
                }
                worker!!.get(20, TimeUnit.SECONDS)
                if (withdraw) {
                    assertFalse(usageRecords.existsById(reservation.turnId))
                    assertEquals(null, usage.findClientRequest(owner.id, requestId))
                } else {
                    assertEquals(AiUsageStatus.COMPLETED, usageRecords.findById(reservation.turnId).orElseThrow().status)
                    assertEquals(AiClientRequestStatus.COMPLETED, usage.findClientRequest(owner.id, requestId)!!.status)
                    assertEquals("보존할 답변", history.detail(owner.id, reservation.turnId).versions.single().answer)
                }
            } finally { executor.shutdownNow() }
        }
    }

    private fun testAnswer(turnId: UUID) = GroundedAnswer(null, turnId, "보존할 답변", emptyList(), 0, 0, BigDecimal.ZERO, false)

    private fun user() = users.save(UserEntity(email = "history-${UUID.randomUUID()}@example.com", displayName = "History Test"))
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun postgres(registry: DynamicPropertyRegistry) = AiHistoryTestConfiguration.postgres(registry)
    }

}
