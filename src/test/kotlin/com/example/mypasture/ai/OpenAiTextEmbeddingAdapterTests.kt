package com.example.mypasture.ai

import com.example.mypasture.ai.infrastructure.OpenAiTextEmbeddingAdapter
import com.example.mypasture.config.AiProperties
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer

/** 운영 batch 크기의 OpenAI embedding 응답과 전용 메모리 상한을 검증합니다. */
class OpenAiTextEmbeddingAdapterTests {
    @Test
    fun `accepts embedding response larger than WebClient default buffer`() {
        val inputCount = 20
        val responseBody = embeddingResponse(inputCount)
        check(responseBody.toByteArray().size > 256 * 1024)
        val server = embeddingServer(responseBody)

        try {
            val adapter = OpenAiTextEmbeddingAdapter(
                properties("http://127.0.0.1:${server.port()}").copy(
                    embeddingResponseMaxBytes = 1024 * 1024,
                ),
            )

            val result = runBlocking { adapter.embed(List(inputCount) { "성경 본문 $it" }) }

            assertEquals(inputCount, result.vectors.size)
            assertEquals(1_536, result.vectors.first().size)
            assertEquals(42, result.promptTokens)
        } finally {
            server.disposeNow()
        }
    }

    @Test
    fun `rejects unsafe response buffer configuration`() {
        assertThrows(IllegalArgumentException::class.java) {
            OpenAiTextEmbeddingAdapter(
                properties("http://127.0.0.1").copy(embeddingResponseMaxBytes = 128 * 1024),
            )
        }
    }

    private fun embeddingServer(body: String): DisposableServer =
        HttpServer.create()
            .host("127.0.0.1")
            .port(0)
            .route { routes ->
                routes.post("/v1/embeddings") { _, response ->
                    response.header("Content-Type", "application/json")
                        .sendString(Mono.just(body))
                }
            }
            .bindNow()

    private fun embeddingResponse(count: Int): String {
        val vector = List(1_536) { "0.123456789" }.joinToString(",")
        val data = (0 until count).joinToString(",") { index ->
            "{\"index\":$index,\"embedding\":[$vector]}"
        }
        return "{\"data\":[$data],\"usage\":{\"prompt_tokens\":42}}"
    }

    private fun properties(baseUrl: String) = AiProperties(
        model = "gpt-5-mini",
        embeddingModel = "text-embedding-3-small",
        embeddingDimensions = 1_536,
        embeddingBatchSize = 100,
        embeddingIndexingEnabled = false,
        embeddingIndexPollDelayMs = 10_000,
        embeddingInputCostPerMillion = BigDecimal("0.02"),
        apiKey = "test",
        baseUrl = baseUrl,
        maxInputChars = 300,
        maxOutputTokens = 700,
        freeDailyLimit = 10,
        timeoutMs = 12_000,
        cacheTtlSeconds = 86_400,
        ragTopK = 10,
        ragMinSimilarity = 0.35,
        ragLexicalFallbackEnabled = true,
        commentaryGenerationEnabled = false,
        commentaryPollDelayMs = 5_000,
        commentaryPromptVersion = "chapter-commentary-v1",
        commentaryMaxOutputTokens = 1_800,
        commentaryInputCostPerMillion = BigDecimal("0.25"),
        commentaryOutputCostPerMillion = BigDecimal("2.00"),
    )
}
