package com.example.mypasture.bible.infrastructure

import com.example.mypasture.ai.domain.AiModelCatalog
import com.example.mypasture.ai.domain.AiRuntimeConfigSnapshot
import com.example.mypasture.bible.application.BibleCommentaryEvaluation
import com.example.mypasture.bible.application.BibleCommentaryEvaluatorPort
import com.example.mypasture.bible.application.BibleCommentaryEvidence
import com.example.mypasture.bible.application.GeneratedBibleCommentary
import com.example.mypasture.bible.domain.BibleChapter
import com.example.mypasture.config.AiProperties
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

/** 생성 모델과 분리된 OpenAI 평가 모델로 장별 해설의 근거성과 신학적 안전성을 채점합니다. */
@Component
@ConditionalOnProperty(name = ["app.ai.commentary-generation-enabled"], havingValue = "true")
class OpenAiBibleCommentaryEvaluator(
    private val properties: AiProperties,
    private val objectMapper: ObjectMapper,
) : BibleCommentaryEvaluatorPort {
    private val webClient: WebClient = WebClient.builder()
        .baseUrl(properties.baseUrl)
        .clientConnector(
            ReactorClientHttpConnector(
                HttpClient.create()
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.timeoutMs.toInt())
                    .responseTimeout(Duration.ofMillis(properties.timeoutMs)),
            ),
        )
        .build()

    override suspend fun evaluate(
        chapter: BibleChapter,
        evidence: List<BibleCommentaryEvidence>,
        commentary: GeneratedBibleCommentary,
        config: AiRuntimeConfigSnapshot,
    ): BibleCommentaryEvaluation {
        if (!config.evaluationEnabled) {
            return BibleCommentaryEvaluation(
                passed = true,
                groundingScore = 5,
                historicalEvidenceScore = 5,
                referenceRelevanceScore = 5,
                theologicalNeutralityScore = 5,
                clarityScore = 5,
                fabricatedScripture = false,
                unsupportedHistoricalClaims = false,
                issues = emptyList(),
                model = "disabled",
                promptTokens = 0,
                completionTokens = 0,
                estimatedCostUsd = BigDecimal.ZERO,
            )
        }

        val response = executeWithRetry(buildRequest(chapter, evidence, commentary, config))
        val payload: EvaluationPayload = objectMapper.readValue(extractOutputText(response))
        val usage = response.path("usage")
        val promptTokens = usage.path("input_tokens").asInt(0)
        val completionTokens = usage.path("output_tokens").asInt(0)
        return BibleCommentaryEvaluation(
            passed = payload.passed,
            groundingScore = payload.groundingScore,
            historicalEvidenceScore = payload.historicalEvidenceScore,
            referenceRelevanceScore = payload.referenceRelevanceScore,
            theologicalNeutralityScore = payload.theologicalNeutralityScore,
            clarityScore = payload.clarityScore,
            fabricatedScripture = payload.fabricatedScripture,
            unsupportedHistoricalClaims = payload.unsupportedHistoricalClaims,
            issues = payload.issues,
            model = config.evaluatorModel,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            estimatedCostUsd = estimateCost(config.evaluatorModel, promptTokens, completionTokens),
        )
    }

    private fun buildRequest(
        chapter: BibleChapter,
        evidence: List<BibleCommentaryEvidence>,
        commentary: GeneratedBibleCommentary,
        config: AiRuntimeConfigSnapshot,
    ): Map<String, Any> {
        val source = chapter.verses.joinToString("\n") { "${it.verse}절 ${it.text}" }
        val context = evidence.joinToString("\n\n") {
            "[${it.evidenceType}] ${it.title}\n${it.content}\n출처: ${it.sourceName}"
        }.ifBlank { "승인된 외부 배경 메모 없음" }
        val candidate = objectMapper.writeValueAsString(commentary)
        return mapOf(
            "model" to config.evaluatorModel,
            "instructions" to """
                당신은 성경 장별 해설의 독립 품질 검수자입니다.
                SOURCE_CHAPTER와 APPROVED_CONTEXT만 사실 판단의 근거로 사용하세요.
                각 점수는 1부터 5까지이며, 근거 없는 역사적 주장은 반드시 표시하세요.
                존재하지 않는 성경 문장이나 본문과 다른 인용이 있으면 fabricatedScripture를 true로 설정하세요.
                교단마다 견해가 갈릴 수 있는 내용을 하나의 확정적 사실처럼 말하면 theologicalNeutralityScore를 낮추세요.
                issues에는 수정 가능한 구체적 문제만 한국어로 작성하세요.
            """.trimIndent(),
            "input" to """
                SOURCE_CHAPTER:
                $source

                APPROVED_CONTEXT:
                $context

                CANDIDATE_COMMENTARY:
                $candidate
            """.trimIndent(),
            "max_output_tokens" to config.evaluationMaxOutputTokens,
            "text" to mapOf(
                "format" to mapOf(
                    "type" to "json_schema",
                    "name" to "bible_commentary_evaluation",
                    "strict" to true,
                    "schema" to evaluationSchema(),
                ),
            ),
        )
    }

    private suspend fun executeWithRetry(body: Map<String, Any>): JsonNode {
        var lastFailure: Exception? = null
        repeat(3) { attempt ->
            try {
                return webClient.post()
                    .uri("/v1/responses")
                    .header("Authorization", "Bearer ${properties.apiKey}")
                    .bodyValue(body)
                    .retrieve()
                    .awaitBody()
            } catch (exception: WebClientResponseException) {
                if (exception.statusCode.value() != 429 && !exception.statusCode.is5xxServerError) throw exception
                lastFailure = exception
                if (attempt < 2) delay(500L * (attempt + 1))
            } catch (exception: WebClientRequestException) {
                lastFailure = exception
                if (attempt < 2) delay(500L * (attempt + 1))
            } catch (exception: CancellationException) {
                throw exception
            }
        }
        throw lastFailure ?: IllegalStateException("OpenAI 해설 평가에 실패했습니다.")
    }

    private fun evaluationSchema(): Map<String, Any> {
        val score = mapOf("type" to "integer", "minimum" to 1, "maximum" to 5)
        val properties = mapOf(
            "passed" to mapOf("type" to "boolean"),
            "groundingScore" to score,
            "historicalEvidenceScore" to score,
            "referenceRelevanceScore" to score,
            "theologicalNeutralityScore" to score,
            "clarityScore" to score,
            "fabricatedScripture" to mapOf("type" to "boolean"),
            "unsupportedHistoricalClaims" to mapOf("type" to "boolean"),
            "issues" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
        )
        return mapOf(
            "type" to "object",
            "properties" to properties,
            "required" to properties.keys.toList(),
            "additionalProperties" to false,
        )
    }

    private fun extractOutputText(response: JsonNode): String {
        response.path("output").forEach { output ->
            output.path("content").forEach { content ->
                if (content.path("type").asText() == "output_text") return content.path("text").asText()
            }
        }
        error("OpenAI 해설 평가 구조화 출력이 없습니다.")
    }

    private fun estimateCost(model: String, promptTokens: Int, completionTokens: Int): BigDecimal {
        val pricing = AiModelCatalog.pricing(model) ?: return BigDecimal.ZERO
        val input = BigDecimal(promptTokens).divide(BigDecimal(1_000_000)).multiply(pricing.inputCostPerMillionUsd)
        val output = BigDecimal(completionTokens).divide(BigDecimal(1_000_000)).multiply(pricing.outputCostPerMillionUsd)
        return input.add(output)
    }
}

private data class EvaluationPayload(
    val passed: Boolean,
    val groundingScore: Int,
    val historicalEvidenceScore: Int,
    val referenceRelevanceScore: Int,
    val theologicalNeutralityScore: Int,
    val clarityScore: Int,
    val fabricatedScripture: Boolean,
    val unsupportedHistoricalClaims: Boolean,
    val issues: List<String>,
)
