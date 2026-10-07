package com.example.mypasture.ai

import org.springframework.test.web.servlet.MvcResult
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

/** MVC 완료, 완전한 SSE 종료 frame, runner 종료를 하나의 제한 시간 안에서 각각 확인합니다. */
internal object AiSseTestSupport {
    private const val TIMEOUT_MS = 15_000L

    /** SSE의 blank line까지 기록된 frame만 완료 event로 인정합니다. */
    data class Frame(val event: String, val data: String)

    fun frames(body: String): List<Frame> = body.replace("\r\n", "\n").split("\n\n").dropLast(1).mapNotNull { block ->
        val lines = block.lines()
        val event = lines.firstOrNull { it.startsWith("event:") }?.substringAfter(":")?.trim() ?: return@mapNotNull null
        Frame(event, lines.filter { it.startsWith("data:") }.joinToString("\n") { it.substringAfter(":").trimStart() })
    }

    fun awaitCompletion(result: MvcResult, isIdle: () -> Boolean, terminal: String, requiredEvents: Set<String>): List<Frame> {
        assertTrue(result.request.isAsyncStarted, "MVC did not initialize an async request")
        return awaitCompletion(
            body = { result.response.contentAsString },
            awaitMvc = { remainingMs -> result.getAsyncResult(remainingMs); Unit },
            isIdle = isIdle,
            terminal = terminal,
            requiredEvents = requiredEvents,
        )
    }

    /** 가짜 clock을 주입해 누락/미완료 조건도 실제 sleep 없이 회귀 검증합니다. */
    fun awaitCompletion(
        body: () -> String,
        awaitMvc: (Long) -> Unit,
        isIdle: () -> Boolean,
        terminal: String,
        requiredEvents: Set<String>,
        timeoutMs: Long = TIMEOUT_MS,
        now: () -> Long = System::nanoTime,
        pause: () -> Unit = { Thread.sleep(10) },
    ): List<Frame> {
        val deadline = now() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        awaitCondition("SSE terminal '$terminal' frame", deadline, now, pause) {
            val events = frames(body()).map { it.event }
            assertTrue(terminal == "error" || "error" !in events, "Unexpected SSE error while awaiting '$terminal'")
            terminal in events
        }
        val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - now()).coerceAtLeast(1)
        try {
            awaitMvc(remainingMs)
        } catch (error: IllegalStateException) {
            throw AssertionError("MVC async completion did not arrive after SSE '$terminal'", error)
        }
        assertTrue(now() <= deadline, "MVC async completion exceeded the shared deadline")
        awaitCondition("AI streaming runner idle", deadline, now, pause, isIdle)
        val completed = frames(body())
        val missing = requiredEvents - completed.map { it.event }.toSet()
        assertTrue(missing.isEmpty(), "Completed SSE response is missing required events: $missing")
        return completed
    }

    fun awaitIdle(isIdle: () -> Boolean) {
        awaitCondition("AI streaming runner idle", System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS), System::nanoTime, { Thread.sleep(10) }, isIdle)
    }

    private fun awaitCondition(phase: String, deadline: Long, now: () -> Long, pause: () -> Unit, ready: () -> Boolean) {
        while (true) {
            assertTrue(now() <= deadline, "$phase did not complete before the bounded deadline")
            if (ready()) return
            assertTrue(now() < deadline, "$phase did not complete before the bounded deadline")
            pause()
        }
    }
}
