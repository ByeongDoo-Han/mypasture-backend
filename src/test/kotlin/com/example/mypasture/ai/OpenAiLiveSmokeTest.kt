package com.example.mypasture.ai

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertTrue

/** 실제 API key, 모델 접근 권한, Responses API 과금 요청을 최소 토큰으로 함께 확인합니다. */
@Tag("openai-live")
class OpenAiLiveSmokeTest {
    private val objectMapper = jacksonObjectMapper()
    // 로컬 smoke test는 셸에 남은 오래된 키보다 명시적인 .env.local 값을 우선합니다.
    private val environment = System.getenv() + DotEnv.load(Path.of(".env.local"))
    private val baseUrl = environment["OPENAI_BASE_URL"]?.trimEnd('/') ?: "https://api.openai.com"
    private val apiKey = environment.requiredSecret("OPENAI_API_KEY")
    private val applicationModel = environment["OPENAI_MODEL"]?.ifBlank { null } ?: "gpt-5-mini"
    private val smokeModel = environment["OPENAI_SMOKE_TEST_MODEL"]?.ifBlank { null } ?: "gpt-4.1-nano"
    private val maxOutputTokens = environment["OPENAI_SMOKE_MAX_OUTPUT_TOKENS"]
        ?.toIntOrNull()
        ?.coerceIn(MIN_OUTPUT_TOKENS, MAX_OUTPUT_TOKEN_CEILING)
        ?: DEFAULT_MAX_OUTPUT_TOKENS
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    @Test
    fun `설정된 키로 앱 모델에 접근하고 최소 응답을 생성할 수 있다`() {
        verifyModelAccess(applicationModel)

        val requestBody = objectMapper.writeValueAsString(
            mapOf(
                "model" to smokeModel,
                "input" to "Reply OK.",
                "max_output_tokens" to maxOutputTokens,
                "store" to false,
            ),
        )
        val response = send(
            HttpRequest.newBuilder(URI.create("$baseUrl/v1/responses"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build(),
        )
        val body = objectMapper.readTree(response.body())
        val status = body.path("status").asText()
        val inputTokens = body.path("usage").path("input_tokens").asInt(-1)
        val outputTokens = body.path("usage").path("output_tokens").asInt(-1)
        val totalTokens = body.path("usage").path("total_tokens").asInt(-1)

        assertTrue(body.path("id").asText().startsWith("resp_"), "OpenAI response id is missing")
        assertTrue(status == "completed", "Unexpected OpenAI response status: $status")
        assertTrue(inputTokens > 0, "OpenAI input token usage is missing")
        assertTrue(outputTokens in 0..maxOutputTokens, "OpenAI output token limit was not respected")
        assertTrue(totalTokens >= inputTokens + outputTokens, "OpenAI total token usage is invalid")

        println(
            "OpenAI smoke test passed: model=$smokeModel, status=$status, " +
                "inputTokens=$inputTokens, outputTokens=$outputTokens, totalTokens=$totalTokens",
        )
    }

    /** 모델 조회 API는 생성 토큰을 소비하지 않으면서 운영 모델 접근 권한을 먼저 검증합니다. */
    private fun verifyModelAccess(model: String) {
        val encodedModel = URLEncoder.encode(model, StandardCharsets.UTF_8).replace("+", "%20")
        send(
            HttpRequest.newBuilder(URI.create("$baseUrl/v1/models/$encodedModel"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer $apiKey")
                .GET()
                .build(),
        )
    }

    private fun send(request: HttpRequest): HttpResponse<String> {
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() in 200..299) return response

        val error = runCatching { objectMapper.readTree(response.body()).path("error") }.getOrNull()
        val code = error?.path("code")?.asText()?.ifBlank { "unknown" } ?: "unknown"
        val type = error?.path("type")?.asText()?.ifBlank { "unknown" } ?: "unknown"
        error("OpenAI request failed: status=${response.statusCode()}, type=$type, code=$code")
    }

    private fun Map<String, String>.requiredSecret(name: String): String =
        get(name)?.takeIf { it.startsWith("sk-") }
            ?: error("$name must be configured in .env.local or the process environment")

    companion object {
        private const val DEFAULT_MAX_OUTPUT_TOKENS = 16
        private const val MIN_OUTPUT_TOKENS = 16
        private const val MAX_OUTPUT_TOKEN_CEILING = 32
    }
}

/** 테스트 프로세스 안에서만 gitignored dotenv 값을 읽으며 값 자체는 출력하지 않습니다. */
private object DotEnv {
    fun load(path: Path): Map<String, String> {
        if (!Files.isRegularFile(path)) return emptyMap()
        return Files.readAllLines(path)
            .asSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith('#') && it.contains('=') }
            .associate { line ->
                val (name, value) = line.split('=', limit = 2)
                name.trim() to value.trim().removeSurrounding("\"").removeSurrounding("'")
            }
    }
}
