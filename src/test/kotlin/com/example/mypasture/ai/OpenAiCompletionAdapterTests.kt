package com.example.mypasture.ai

import com.example.mypasture.ai.application.AiProviderException
import com.example.mypasture.ai.domain.BibleCitation
import com.example.mypasture.ai.domain.AiReasoningEffort
import com.example.mypasture.ai.domain.AiRuntimeConfigSnapshot
import com.example.mypasture.ai.domain.AiUseCase
import com.example.mypasture.ai.infrastructure.AiCitationValidator
import com.example.mypasture.ai.infrastructure.OpenAiCompletionAdapter
import com.example.mypasture.config.AiProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlinx.coroutines.test.runTest
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
    fun `streams output text deltas and returns completed usage`() = runTest {
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
    fun `sets low reasoning for gpt 5 models`() = runTest {
        val requestBodies = mutableListOf<String>()
        val port = startServer { exchange ->
            requestBodies += exchange.requestBody.bufferedReader().readText()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.bufferedWriter().use { writer ->
                writer.write("""{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"근거: [42] 답변"}]}],"usage":{}}""")
            }
        }

        adapter(port, model = "gpt-5-mini").answerWithCitations("질문", listOf(citation()))

        assertTrue(requestBodies.single().contains("\"reasoning\":{\"effort\":\"low\"}"))
    }

    @Test
    fun `evaluates answer with strict structured output and records evaluator usage`() = runTest {
        val requestBodies = mutableListOf<String>()
        val port = startServer { exchange ->
            requestBodies += exchange.requestBody.bufferedReader().readText()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.bufferedWriter().use { writer ->
                writer.write(
                    """{"output":[{"type":"message","content":[{"type":"output_text","text":"{\"passed\":true,\"grounding_score\":5,\"citation_score\":5,\"safety_score\":5,\"relevance_score\":4,\"pastoral_tone_score\":5,\"fabricated_scripture\":false,\"uncertain\":false,\"issues\":[]}"}]}],"usage":{"input_tokens":10,"output_tokens":5}}""",
                )
            }
        }

        val result = adapter(port).evaluate("질문", listOf(citation()), "근거: [42] 답변", runtimeConfig())

        assertTrue(result.passed)
        assertEquals(4, result.relevanceScore)
        assertEquals(10, result.promptTokens)
        assertTrue(requestBodies.single().contains("\"name\":\"bible_answer_evaluation\""))
        assertTrue(requestBodies.single().contains("\"strict\":true"))
        assertTrue(requestBodies.single().contains("\"model\":\"gpt-5.6-luna\""))
    }

    @Test
    fun `retries a transient response without blocking a thread`() = runTest {
        val calls = AtomicInteger()
        val port = startServer { exchange ->
            if (calls.incrementAndGet() == 1) {
                exchange.sendResponseHeaders(429, -1)
            } else {
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.bufferedWriter().use { writer ->
                    writer.write("""{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"근거: [42] 답변"}]}],"usage":{}}""")
                }
            }
        }

        val answer = adapter(port).answerWithCitations("질문", listOf(citation()))

        assertEquals("근거: [42] 답변", answer.answer)
        assertEquals(2, calls.get())
    }

    @Test
    fun `does not retry after a delta was already delivered`() = runTest {
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

        val error = runCatching { adapter(port).answerWithCitations("질문", listOf(citation())) { } }.exceptionOrNull()
        assertTrue(error is AiProviderException)
        assertEquals(1, calls.get())
    }

    @Test
    fun `does not expose an unknown citation in streamed deltas`() = runTest {
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

        val error = runCatching { adapter(port).answerWithCitations("질문", listOf(citation())) { delivered += it } }.exceptionOrNull()
        assertTrue(error is AiProviderException)
        assertTrue(delivered.isEmpty())
    }

    private fun startServer(handler: (com.sun.net.httpserver.HttpExchange) -> Unit): Int {
        server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/v1/responses", handler)
            start()
        }
        return server!!.address.port
    }

    private fun adapter(port: Int, model: String = "test-model") =
        OpenAiCompletionAdapter(properties(port).copy(model = model), AiCitationValidator(), jacksonObjectMapper())

    private fun citation() = BibleCitation(42, "KOR1910", "시편", 23, 1, "여호와는 나의 목자시니")

    private fun runtimeConfig() = AiRuntimeConfigSnapshot(
        id = null,
        useCase = AiUseCase.AI_PASTOR,
        version = 0,
        generatorModel = "gpt-5.6-terra",
        evaluatorModel = "gpt-5.6-luna",
        generatorReasoningEffort = AiReasoningEffort.LOW,
        evaluatorReasoningEffort = AiReasoningEffort.MEDIUM,
        maxOutputTokens = 100,
        evaluationMaxOutputTokens = 100,
        evaluationEnabled = true,
        evaluationThreshold = 4,
        maxRevisionCount = 1,
        source = "TEST",
    )

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
