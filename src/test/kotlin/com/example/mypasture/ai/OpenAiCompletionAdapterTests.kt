package com.example.mypasture.ai

import com.example.mypasture.ai.application.AiProviderException
import com.example.mypasture.ai.domain.BibleCitation
import com.example.mypasture.ai.infrastructure.AiCitationValidator
import com.example.mypasture.ai.infrastructure.OpenAiCompletionAdapter
import com.example.mypasture.config.AiProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/** OpenAI Responses API SSE 이벤트를 실제 HTTP 연결에서 순서대로 해석하는지 검증합니다. */
class OpenAiCompletionAdapterTests {
    private var server: HttpServer? = null

    @AfterEach
    fun stopServer() {
        server?.stop(0)
    }

    @Test
    fun `streams output text deltas and returns completed usage`() {
        val requestBodies = mutableListOf<String>()
        val port = startServer { exchange ->
            requestBodies += exchange.requestBody.bufferedReader().readText()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.bufferedWriter().use { writer ->
                writer.write("data: {\"type\":\"response.output_text.delta\",\"delta\":\"말씀 \"}\n\n")
                writer.flush()
                writer.write("data: {\"type\":\"response.output_text.delta\",\"delta\":\"[42]\"}\n\n")
                writer.flush()
                writer.write("data: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":11,\"output_tokens\":7}}}\n\n")
            }
        }
        val deltas = mutableListOf<String>()

        val answer = adapter(port).answerWithCitations("불안합니다", listOf(citation())) { deltas += it }

        assertEquals(listOf("말씀 [42]"), deltas)
        assertEquals("말씀 [42]", answer.answer)
        assertEquals(11, answer.promptTokens)
        assertEquals(7, answer.completionTokens)
        assertEquals(listOf(42L), answer.citations.map { it.verseId })
        assertTrue(requestBodies.single().contains("\"stream\":true"))
    }

    @Test
    fun `does not retry after a delta was already delivered`() {
        val calls = AtomicInteger()
        val port = startServer { exchange ->
            calls.incrementAndGet()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.bufferedWriter().use { writer ->
                writer.write("data: {\"type\":\"response.output_text.delta\",\"delta\":\"부분 \"}\n\n")
                writer.flush()
            }
        }

        assertThrows(AiProviderException::class.java) {
            adapter(port).answerWithCitations("질문", listOf(citation())) { }
        }
        assertEquals(1, calls.get())
    }

    @Test
    fun `does not expose an unknown citation in streamed deltas`() {
        val port = startServer { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.bufferedWriter().use { writer ->
                writer.write("data: {\"type\":\"response.output_text.delta\",\"delta\":\"근거 없는 답 [999]\"}\n\n")
                writer.flush()
                writer.write("data: {\"type\":\"response.completed\",\"response\":{\"usage\":{}}}\n\n")
            }
        }
        val delivered = mutableListOf<String>()

        assertThrows(AiProviderException::class.java) {
            adapter(port).answerWithCitations("질문", listOf(citation())) { delivered += it }
        }
        assertTrue(delivered.isEmpty())
    }

    private fun startServer(handler: (com.sun.net.httpserver.HttpExchange) -> Unit): Int {
        server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/v1/responses", handler)
            start()
        }
        return server!!.address.port
    }

    private fun adapter(port: Int) =
        OpenAiCompletionAdapter(properties(port), AiCitationValidator(), jacksonObjectMapper())

    private fun citation() = BibleCitation(42, "KOR1910", "시편", 23, 1, "여호와는 나의 목자시니")

    private fun properties(port: Int) =
        AiProperties(
            model = "test-model",
            embeddingModel = "test-embedding",
            embeddingDimensions = 3,
            embeddingBatchSize = 10,
            embeddingIndexingEnabled = false,
            embeddingIndexPollDelayMs = 10_000,
            embeddingInputCostPerMillion = BigDecimal("0.02"),
            apiKey = "test",
            baseUrl = "http://localhost:$port",
            maxInputChars = 300,
            maxOutputTokens = 100,
            freeDailyLimit = 10,
            timeoutMs = 2_000,
            cacheTtlSeconds = 60,
            ragTopK = 3,
            ragMinSimilarity = 0.3,
            ragLexicalFallbackEnabled = true,
            commentaryGenerationEnabled = false,
            commentaryPollDelayMs = 5_000,
            commentaryPromptVersion = "test",
            commentaryMaxOutputTokens = 100,
            commentaryInputCostPerMillion = BigDecimal("0.25"),
            commentaryOutputCostPerMillion = BigDecimal("2.00"),
            streamTimeoutMs = 2_000,
        )
}
