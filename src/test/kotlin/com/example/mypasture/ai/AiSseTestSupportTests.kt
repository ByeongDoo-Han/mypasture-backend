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
    fun `종료 이벤트가 5초 뒤 와도 MVC와 실행기가 공동 시간 제한 안에 끝나면 성공한다`() {
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
    fun `데이터 안의 종료 표시와 미완성 종료 프레임은 완료로 처리하지 않는다`() {
        for (body in listOf(frame("delta", "event:done"), completeBody().removeSuffix("\n\n"))) {
            val failure = failureFor(body = body)
            assertTrue(failure.message!!.contains("SSE terminal 'done' frame"))
        }
    }

    @Test
    fun `종료 뒤에도 필수 성공 이벤트가 모두 있어야 한다`() {
        for (missing in required - "done") {
            val failure = failureFor(body = completeBody(required - missing))
            assertTrue(failure.message!!.contains("missing required events: [$missing]"))
        }
        assertTrue(failureFor(body = completeBody(required - "done")).message!!.contains("SSE terminal"))
    }

    @Test
    fun `종료 이벤트 뒤 MVC 완료가 없으면 별도 오류로 실패한다`() {
        val failure = failureFor(awaitMvc = { throw IllegalStateException("Async result not set") })
        assertTrue(failure.message!!.contains("MVC async completion"))
        assertTrue(failure.cause is IllegalStateException)
    }

    @Test
    fun `MVC는 공동 시간 제한의 남은 시간을 넘겨 사용할 수 없다`() {
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
    fun `MVC와 종료 이벤트가 완료돼도 실행기가 미완료면 성공으로 숨기지 않는다`() {
        assertTrue(failureFor(isIdle = { false }).message!!.contains("AI streaming runner idle"))
    }

    @Test
    fun `오류 이벤트는 성공 완료로 처리하지 않는다`() {
        assertTrue(failureFor(body = frame("error", "{\"code\":\"AI_GROUNDING_NOT_FOUND\"}")).message!!.contains("Unexpected SSE error"))
    }

    @Test
    fun `오류 종료에는 별도 시간 제한 경로를 적용한다`() {
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
