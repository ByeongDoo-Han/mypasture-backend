package com.example.mypasture.bible.infrastructure

import com.example.mypasture.bible.application.BibleCommentaryGeneratorPort
import com.example.mypasture.bible.application.GeneratedBibleCommentary
import com.example.mypasture.bible.application.GeneratedCommentaryReference
import com.example.mypasture.bible.application.GeneratedCommentarySection
import com.example.mypasture.bible.domain.BibleCatalog
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

/** OpenAI Responses API의 JSON Schema 출력으로 검수 전 장별 해설 초안을 생성합니다. */
@Component
@ConditionalOnProperty(name = ["app.ai.commentary-generation-enabled"], havingValue = "true")
class OpenAiBibleCommentaryGenerator(
    private val properties: AiProperties,
    private val objectMapper: ObjectMapper,
) : BibleCommentaryGeneratorPort {
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

    override suspend fun generate(chapter: BibleChapter): GeneratedBibleCommentary {
        val requestBody = buildRequest(chapter)
        val response = executeWithRetry(requestBody)
        val usage = response.path("usage")
        val promptTokens = usage.path("input_tokens").asInt(0)
        val completionTokens = usage.path("output_tokens").asInt(0)
        val payload: CommentaryPayload = objectMapper.readValue(extractOutputText(response))

        return GeneratedBibleCommentary(
            summary = payload.summary,
            historicalContext = payload.historicalContext,
            sections = payload.sections.map {
                GeneratedCommentarySection(it.startVerse, it.endVerse, it.title, it.explanation)
            },
            keyThemes = payload.keyThemes,
            crossReferences = payload.crossReferences.map {
                GeneratedCommentaryReference(it.bookCode, it.chapter, it.verse, it.reason)
            },
            reflectionQuestions = payload.reflectionQuestions,
            cautions = payload.cautions,
            model = properties.model,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            estimatedCostUsd = estimateCost(promptTokens, completionTokens),
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
        throw lastFailure ?: IllegalStateException("OpenAI 해설 생성에 실패했습니다.")
    }

    private fun buildRequest(chapter: BibleChapter): Map<String, Any> {
        val canonicalCodes = BibleCatalog.books.joinToString(",") { it.code }
        val source = chapter.verses.joinToString("\n") { "${it.verse}절 ${it.text}" }
        val instructions =
            """
            당신은 한국어 성경 해설 편집자입니다. 제공된 SOURCE_CHAPTER만 이 장의 직접 근거로 사용하세요.
            성경 문장을 직접 인용하거나 새로 작성하지 말고 장·절 위치만 반환하세요.
            sections는 1절부터 마지막 절까지 중복과 누락 없이 연속 구간으로 나누세요.
            역사적 사실과 해석을 구분하고, 논쟁적인 내용은 단정하지 마세요.
            crossReferences의 bookCode는 다음 코드 중 하나만 사용하세요: $canonicalCodes
            확실하지 않은 교차 참조는 포함하지 마세요. 결과는 지정된 JSON Schema만 따르세요.
            """.trimIndent()
        val input =
            """
            VERSION: ${chapter.version}
            BOOK: ${chapter.book.code} (${chapter.book.name})
            CHAPTER: ${chapter.chapter}
            LAST_VERSE: ${chapter.verses.maxOf { it.verse }}

            SOURCE_CHAPTER:
            $source
            """.trimIndent()

        return mapOf(
            "model" to properties.model,
            "instructions" to instructions,
            "input" to input,
            "max_output_tokens" to properties.commentaryMaxOutputTokens,
            "text" to mapOf(
                "format" to mapOf(
                    "type" to "json_schema",
                    "name" to "bible_chapter_commentary",
                    "strict" to true,
                    "schema" to commentarySchema(),
                ),
            ),
        )
    }

    private fun commentarySchema(): Map<String, Any> {
        val sectionSchema = objectSchema(
            mapOf(
                "startVerse" to integerSchema(),
                "endVerse" to integerSchema(),
                "title" to stringSchema(),
                "explanation" to stringSchema(),
            ),
        )
        val referenceSchema = objectSchema(
            mapOf(
                "bookCode" to stringSchema(),
                "chapter" to integerSchema(),
                "verse" to integerSchema(),
                "reason" to stringSchema(),
            ),
        )
        return objectSchema(
            mapOf(
                "summary" to stringSchema(),
                "historicalContext" to stringSchema(),
                "sections" to arraySchema(sectionSchema),
                "keyThemes" to arraySchema(stringSchema()),
                "crossReferences" to arraySchema(referenceSchema),
                "reflectionQuestions" to arraySchema(stringSchema()),
                "cautions" to arraySchema(stringSchema()),
            ),
        )
    }

    private fun objectSchema(properties: Map<String, Any>): Map<String, Any> =
        mapOf(
            "type" to "object",
            "properties" to properties,
            "required" to properties.keys.toList(),
            "additionalProperties" to false,
        )

    private fun arraySchema(items: Map<String, Any>): Map<String, Any> = mapOf("type" to "array", "items" to items)

    private fun stringSchema(): Map<String, Any> = mapOf("type" to "string")

    private fun integerSchema(): Map<String, Any> = mapOf("type" to "integer")

    private fun extractOutputText(response: JsonNode): String {
        response.path("output").forEach { output ->
            output.path("content").forEach { content ->
                if (content.path("type").asText() == "output_text") {
                    return content.path("text").asText()
                }
            }
        }
        error("OpenAI 구조화 출력이 없습니다.")
    }

    private fun estimateCost(promptTokens: Int, completionTokens: Int): BigDecimal {
        val input = BigDecimal(promptTokens)
            .divide(BigDecimal(1_000_000))
            .multiply(properties.commentaryInputCostPerMillion)
        val output = BigDecimal(completionTokens)
            .divide(BigDecimal(1_000_000))
            .multiply(properties.commentaryOutputCostPerMillion)
        return input.add(output)
    }
}

private data class CommentaryPayload(
    val summary: String,
    val historicalContext: String,
    val sections: List<CommentarySectionPayload>,
    val keyThemes: List<String>,
    val crossReferences: List<CommentaryReferencePayload>,
    val reflectionQuestions: List<String>,
    val cautions: List<String>,
)

private data class CommentarySectionPayload(
    val startVerse: Int,
    val endVerse: Int,
    val title: String,
    val explanation: String,
)

private data class CommentaryReferencePayload(
    val bookCode: String,
    val chapter: Int,
    val verse: Int,
    val reason: String,
)
