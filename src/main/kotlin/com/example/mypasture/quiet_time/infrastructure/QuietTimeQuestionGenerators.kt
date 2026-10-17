package com.example.mypasture.quiet_time.infrastructure

import com.example.mypasture.ai.domain.AiModelCatalog
import com.example.mypasture.config.AiProperties
import com.example.mypasture.quiet_time.application.GeneratedQuietTimeQuestions
import com.example.mypasture.quiet_time.application.QuietTimeQuestionGenerationPort
import com.example.mypasture.quiet_time.domain.QuietTimeQuestionStyle
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
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

/** 선택된 성경 본문만 근거로 OpenAI 구조화 QT 질문을 생성합니다. */
@Component
@ConditionalOnProperty(prefix = "app.ai", name = ["mock-enabled"], havingValue = "false", matchIfMissing = true)
class OpenAiQuietTimeQuestionGenerator(
    private val properties: AiProperties,
    private val objectMapper: ObjectMapper,
) : QuietTimeQuestionGenerationPort {
    private val client = WebClient.builder().baseUrl(properties.baseUrl)
        .clientConnector(ReactorClientHttpConnector(HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.timeoutMs.toInt())
            .responseTimeout(Duration.ofMillis(properties.timeoutMs))))
        .build()

    override suspend fun generate(reference: String, selectedText: String, contextText: String, style: QuietTimeQuestionStyle): GeneratedQuietTimeQuestions {
        val body = mapOf(
            "model" to properties.generatorModel,
            "instructions" to "제공된 성경 본문과 앞뒤 문맥만 근거로 한국어 QT 질문을 만드세요. 관찰은 본문에 명시된 내용을, 묵상은 의미와 감정을, 나눔은 부담 없는 경험 공유를 묻습니다. 탄식이나 고난 본문에 실천을 강요하거나 결과를 보장하지 마세요. 새로운 성경 인용·장절·교리 주장을 만들지 마세요. JSON Schema만 반환하세요.",
            "input" to "REFERENCE: $reference\nSELECTED_VERSES:\n$selectedText\nCONTEXT_VERSES:\n$contextText\nQUESTION_STYLE: ${style.name}",
            "max_output_tokens" to minOf(properties.maxOutputTokens, 1_400),
            "text" to mapOf("format" to mapOf(
                "type" to "json_schema", "name" to "quiet_time_questions", "strict" to true,
                "schema" to mapOf("type" to "object", "additionalProperties" to false,
                    "required" to listOf("observation_questions", "meditation_questions", "sharing_questions", "action_guide", "prayer_guide"),
                    "properties" to mapOf(
                        "observation_questions" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                        "meditation_questions" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                        "sharing_questions" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
                        "action_guide" to mapOf("type" to "string"),
                        "prayer_guide" to mapOf("type" to "string"),
                    )),
            )),
        )
        var lastFailure: Exception? = null
        repeat(2) { attempt ->
            try {
                val response: JsonNode = client.post().uri("/v1/responses")
                    .header("Authorization", "Bearer ${properties.apiKey}")
                    .bodyValue(body).retrieve().awaitBody()
                val output = response.path("output").flatMap { it.path("content").toList() }
                    .firstOrNull { it.path("type").asText() == "output_text" }
                    ?.path("text")?.asText() ?: error("QT 질문 구조화 출력이 없습니다.")
                val payload: QuietTimeQuestionPayload = objectMapper.readValue(output, QuietTimeQuestionPayload::class.java)
                val inputTokens = response.path("usage").path("input_tokens").asInt(0)
                val outputTokens = response.path("usage").path("output_tokens").asInt(0)
                val pricing = AiModelCatalog.pricing(properties.generatorModel)
                val cost = BigDecimal(inputTokens).multiply(pricing?.inputCostPerMillionUsd ?: properties.commentaryInputCostPerMillion)
                    .add(BigDecimal(outputTokens).multiply(pricing?.outputCostPerMillionUsd ?: properties.commentaryOutputCostPerMillion))
                    .divide(BigDecimal(1_000_000))
                return GeneratedQuietTimeQuestions(
                    payload.observationQuestions.map(String::trim), payload.meditationQuestions.map(String::trim),
                    payload.sharingQuestions.map(String::trim), payload.actionGuide.trim(), payload.prayerGuide.trim(),
                    properties.generatorModel, inputTokens, outputTokens, cost,
                ).validate()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: WebClientResponseException) {
                if (exception.statusCode.value() != 429 && !exception.statusCode.is5xxServerError) throw exception
                lastFailure = exception
            } catch (exception: WebClientRequestException) {
                lastFailure = exception
            }
            if (attempt == 0) delay(500)
        }
        throw lastFailure ?: IllegalStateException("QT 질문 생성에 실패했습니다.")
    }
}

/** 로컬·테스트 환경에서 외부 AI 비용 없이 본문 기준 질문 흐름을 검증합니다. */
@Component
@ConditionalOnProperty(prefix = "app.ai", name = ["mock-enabled"], havingValue = "true")
class LocalQuietTimeQuestionGenerator : QuietTimeQuestionGenerationPort {
    override suspend fun generate(reference: String, selectedText: String, contextText: String, style: QuietTimeQuestionStyle) =
        GeneratedQuietTimeQuestions(
            observationQuestions = listOf("$reference 본문에서 반복되거나 강조되는 표현은 무엇인가요?"),
            meditationQuestions = listOf("$reference 말씀은 하나님과 사람에 대해 무엇을 알려 주나요?"),
            sharingQuestions = listOf("$reference 말씀과 연결해 안전하게 나누고 싶은 경험은 무엇인가요?"),
            actionGuide = "오늘 이 말씀에서 발견한 내용을 한 문장으로 기록해 보세요.",
            prayerGuide = "이 말씀을 바르게 이해하고 서로 존중하며 나누도록 도와주세요.",
            model = "local-quiet-time", promptTokens = 0, completionTokens = 0, estimatedCostUsd = BigDecimal.ZERO,
        )
}

/** OpenAI 구조화 출력의 필드 이름을 고정하는 내부 DTO입니다. */
private data class QuietTimeQuestionPayload(
    @JsonProperty("observation_questions") val observationQuestions: List<String>,
    @JsonProperty("meditation_questions") val meditationQuestions: List<String>,
    @JsonProperty("sharing_questions") val sharingQuestions: List<String>,
    @JsonProperty("action_guide") val actionGuide: String,
    @JsonProperty("prayer_guide") val prayerGuide: String,
)
