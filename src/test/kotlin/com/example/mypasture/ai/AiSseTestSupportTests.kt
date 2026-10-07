package com.example.mypasture.ai

import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 대기 단계의 순서와 누락 event/미완료 실패를 가짜 clock으로 결정적으로 검증합니다. */
class AiSseTestSupportTests {
    private val required = setOf("delta", "response", "citations", "usage", "done")

    @Test
    fun `terminal may arrive after five seconds before MVC and runner complete within shared bound`() {
        var elapsedMs = 0L
        var mvcCompleted = false
        val stages = mutableListOf<String>()
        val result = AiSseTestSupport.awaitCompletion(
            body = { if (elapsedMs >= 6_000) completeBody() else frame("status") },
            awaitMvc = { remaining ->
                assertEquals(9_000L, remaining)
                assertEquals(6_000L, elapsedMs)
                stages += "MVC"
                mvcCompleted = true
            },
            isIdle = { mvcCompleted && elapsedMs >= 7_000 },
            terminal = "done",
            requiredEvents = required,
            now = { TimeUnit.MILLISECONDS.toNanos(elapsedMs) },
            pause = { elapsedMs += 1_000 },
        )
        assertEquals(required, result.map { it.event }.toSet())
        assertEquals(listOf("MVC"), stages)
        assertEquals(7_000L, elapsedMs)
    }

    @Test
    fun `marker inside data and unfinished terminal frame cannot complete stream`() {
        for (body in listOf(frame("delta", "event:done"), completeBody().removeSuffix("\n\n"))) {
            val failure = failureFor(body = body)
            assertTrue(failure.message!!.contains("SSE terminal 'done' frame"))
        }
    }

    @Test
    fun `each required success event remains mandatory after terminal completion`() {
        for (missing in required - "done") {
            val failure = failureFor(body = completeBody(required - missing))
            assertTrue(failure.message!!.contains("missing required events: [$missing]"))
        }
        assertTrue(failureFor(body = completeBody(required - "done")).message!!.contains("SSE terminal"))
    }

    @Test
    fun `MVC completion missing after terminal fails distinctly`() {
        val failure = failureFor(awaitMvc = { throw IllegalStateException("Async result not set") })
        assertTrue(failure.message!!.contains("MVC async completion"))
        assertTrue(failure.cause is IllegalStateException)
    }

    @Test
    fun `MVC cannot consume more than remaining shared bound`() {
        var elapsedMs = 0L
        val failure = assertFailsWith<AssertionError> {
            AiSseTestSupport.awaitCompletion(
                body = { completeBody() },
                awaitMvc = { elapsedMs = 15_001 },
                isIdle = { true }, terminal = "done", requiredEvents = required,
                now = { TimeUnit.MILLISECONDS.toNanos(elapsedMs) }, pause = {},
            )
        }
        assertTrue(failure.message!!.contains("shared deadline"))
    }

    @Test
    fun `completed MVC and terminal cannot hide unfinished runner`() {
        assertTrue(failureFor(isIdle = { false }).message!!.contains("AI streaming runner idle"))
    }

    @Test
    fun `error is not successful done completion`() {
        assertTrue(failureFor(body = frame("error", "{\"code\":\"AI_GROUNDING_NOT_FOUND\"}")).message!!.contains("Unexpected SSE error"))
    }

    @Test
    fun `error terminal has its own bounded completion path`() {
        var mvc = false
        val frames = AiSseTestSupport.awaitCompletion(
            body = { frame("error", "{\"code\":\"AI_GROUNDING_NOT_FOUND\"}") },
            awaitMvc = { mvc = true }, isIdle = { mvc }, terminal = "error", requiredEvents = setOf("error"),
        )
        assertEquals("error", frames.single().event)
        assertEquals("{\"code\":\"AI_GROUNDING_NOT_FOUND\"}", frames.single().data)
    }

    private fun failureFor(
        body: String = completeBody(),
        awaitMvc: (Long) -> Unit = {},
        isIdle: () -> Boolean = { true },
    ): AssertionError {
        var elapsedMs = 0L
        return assertFailsWith {
            AiSseTestSupport.awaitCompletion(
                body = { body }, awaitMvc = awaitMvc, isIdle = isIdle,
                terminal = "done", requiredEvents = required, timeoutMs = 100,
                now = { TimeUnit.MILLISECONDS.toNanos(elapsedMs) }, pause = { elapsedMs += 10 },
            )
        }
    }

    private fun completeBody(events: Set<String> = required) = events.joinToString("") { frame(it) }
    private fun frame(event: String, data: String = "{}") = "event:$event\ndata:$data\n\n"
}
