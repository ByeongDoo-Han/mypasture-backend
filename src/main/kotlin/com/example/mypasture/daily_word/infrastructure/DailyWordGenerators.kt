package com.example.mypasture.daily_word.infrastructure

import com.example.mypasture.ai.domain.AiModelCatalog
import com.example.mypasture.ai.domain.AiRuntimeConfigSnapshot
import com.example.mypasture.ai.domain.BibleCitation
import com.example.mypasture.ai.domain.RagRetrievalStrategy
import com.example.mypasture.config.AiProperties
import com.example.mypasture.config.DailyWordProperties
import com.example.mypasture.daily_word.application.DailyWordGenerationPort
import com.example.mypasture.daily_word.domain.DailyWordSource
import com.example.mypasture.daily_word.domain.GeneratedDailyWord
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.netty.channel.ChannelOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.awaitBody
import reactor.netty.http.client.HttpClient
import java.math.BigDecimal
import java.time.Duration

/** 로컬 개발에서 외부 비용 없이 검수 화면을 확인할 수 있는 결정적 초안 생성기입니다. */
@Component
@ConditionalOnProperty(name = ["app.daily-word.provider"], havingValue = "local")
class LocalDailyWordGenerator(
    private val properties: DailyWordProperties,
) : DailyWordGenerationPort {
    override suspend fun generate(
        citation: BibleCitation,
        bookName: String,
        theme: String,
        config: AiRuntimeConfigSnapshot,
    ) = GeneratedDailyWord(
        bookName = bookName,
        meditation = "이 말씀을 천천히 읽으며 $theme 안에서 하나님을 신뢰하는 오늘의 선택을 돌아보세요.",
        actionQuestion = "오늘 이 말씀을 따라 실천할 한 가지는 무엇인가요?",
        source = DailyWordSource.AI,
        model = "local-daily-word",
        promptVersion = properties.promptVersion,
        retrievalStrategy = RagRetrievalStrategy.LEXICAL_FALLBACK,
        embeddingTokens = 0,
        promptTokens = 0,
        completionTokens = 0,
        estimatedCostUsd = BigDecimal.ZERO,
    )
}

/** OpenAI Structured Output으로 실제 RAG 절에 대한 묵상과 질문만 생성합니다. */
@Component
@ConditionalOnProperty(name = ["app.daily-word.provider"], havingValue = "openai")
class OpenAiDailyWordGenerator(
    private val aiProperties: AiProperties,
    private val dailyWordProperties: DailyWordProperties,
    private val objectMapper: ObjectMapper,
) : DailyWordGenerationPort {
    private val webClient = WebClient.builder()
        .baseUrl(aiProperties.baseUrl)
        .clientConnector(
            ReactorClientHttpConnector(
                HttpClient.create()
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, aiProperties.timeoutMs.toInt())
                    .responseTimeout(Duration.ofMillis(aiProperties.timeoutMs)),
            ),
        )
        .build()

    override suspend fun generate(
        citation: BibleCitation,
        bookName: String,
        theme: String,
        config: AiRuntimeConfigSnapshot,
    ): GeneratedDailyWord {
        val response = execute(buildRequest(citation, bookName, theme, config))
        val payload: DailyWordPayload = objectMapper.readValue(extractOutputText(response))
        val meditation = payload.meditation.trim()
        val question = payload.actionQuestion.trim()
        require(meditation.length in 30..1_000) { "오늘의 말씀 묵상 길이가 올바르지 않습니다." }
        require(question.length in 10..300) { "오늘의 말씀 실천 질문 길이가 올바르지 않습니다." }
        val promptTokens = response.path("usage").path("input_tokens").asInt(0)
        val completionTokens = response.path("usage").path("output_tokens").asInt(0)
        val pricing = AiModelCatalog.pricing(config.generatorModel)
        val inputPrice = pricing?.inputCostPerMillionUsd ?: aiProperties.commentaryInputCostPerMillion
        val outputPrice = pricing?.outputCostPerMillionUsd ?: aiProperties.commentaryOutputCostPerMillion
        val cost = BigDecimal(promptTokens).divide(MILLION).multiply(inputPrice)
            .add(BigDecimal(completionTokens).divide(MILLION).multiply(outputPrice))
        return GeneratedDailyWord(
            bookName = bookName,
            meditation = meditation,
            actionQuestion = question,
            source = DailyWordSource.AI,
            model = config.generatorModel,
            promptVersion = dailyWordProperties.promptVersion,
            retrievalStrategy = RagRetrievalStrategy.PGVECTOR,
            embeddingTokens = 0,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            estimatedCostUsd = cost,
        )
    }

    private suspend fun execute(body: Map<String, Any>): JsonNode {
        var lastFailure: Exception? = null
        repeat(3) { attempt ->
            try {
                return webClient.post().uri("/v1/responses")
                    .header("Authorization", "Bearer ${aiProperties.apiKey}")
                    .bodyValue(body).retrieve().awaitBody()
            } catch (exception: WebClientResponseException) {
                if (exception.statusCode.value() != 429 && !exception.statusCode.is5xxServerError) throw exception
                lastFailure = exception
            } catch (exception: WebClientRequestException) {
                lastFailure = exception
            } catch (exception: CancellationException) {
                throw exception
            }
            if (attempt < 2) delay(500L * (attempt + 1))
        }
        throw lastFailure ?: IllegalStateException("오늘의 말씀 AI 생성에 실패했습니다.")
    }

    private fun buildRequest(
        citation: BibleCitation,
        bookName: String,
        theme: String,
        config: AiRuntimeConfigSnapshot,
    ): Map<String, Any> = buildMap {
        put("model", config.generatorModel)
        put(
            "instructions",
            "제공된 SOURCE_VERSE만 근거로 한국어 묵상과 실천 질문을 작성하세요. 성경 구절을 새로 인용하거나 장절을 만들지 마세요. 신학적 논쟁은 단정하지 말고 지정된 JSON Schema만 반환하세요.",
        )
        put(
            "input",
            "THEME: $theme\nSOURCE_VERSE_ID: ${citation.verseId}\nSOURCE_REFERENCE: $bookName ${citation.chapter}:${citation.verse}\nSOURCE_VERSE: ${citation.text}",
        )
        put("max_output_tokens", minOf(config.maxOutputTokens, dailyWordProperties.maxOutputTokens))
        if (config.generatorReasoningEffort.name != "NONE") {
            put("reasoning", mapOf("effort" to config.generatorReasoningEffort.name.lowercase()))
        }
        put(
            "text",
            mapOf(
                "format" to mapOf(
                    "type" to "json_schema",
                    "name" to "daily_word_draft",
                    "strict" to true,
                    "schema" to mapOf(
                        "type" to "object",
                        "additionalProperties" to false,
                        "required" to listOf("meditation", "action_question"),
                        "properties" to mapOf(
                            "meditation" to mapOf("type" to "string"),
                            "action_question" to mapOf("type" to "string"),
                        ),
                    ),
                ),
            ),
        )
    }

    private fun extractOutputText(response: JsonNode): String {
        response.path("output").forEach { output ->
            output.path("content").forEach { content ->
                if (content.path("type").asText() == "output_text") return content.path("text").asText()
            }
        }
        error("오늘의 말씀 구조화 출력이 없습니다.")
    }

    companion object {
        private val MILLION = BigDecimal(1_000_000)
    }
}

private data class DailyWordPayload(
    val meditation: String,
    @JsonProperty("action_question") val actionQuestion: String,
)
