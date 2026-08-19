package com.example.mypasture.ai.infrastructure

import com.example.mypasture.ai.application.AiCompletionPort
import com.example.mypasture.ai.application.AiProviderException
import com.example.mypasture.ai.domain.BibleCitation
import com.example.mypasture.ai.domain.GroundedAnswer
import com.example.mypasture.config.AiProperties
import com.fasterxml.jackson.databind.JsonNode
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import java.math.BigDecimal
import java.time.Duration

/** 운영 환경에서 OpenAI Responses API를 호출하고 RAG로 검색한 성경 구절만 근거로 전달합니다. */
@Component
@ConditionalOnProperty(prefix = "app.ai", name = ["mock-enabled"], havingValue = "false", matchIfMissing = true)
class OpenAiCompletionAdapter(
    private val properties: AiProperties,
    private val citationValidator: AiCitationValidator,
) : AiCompletionPort {
    private val client = RestClient.builder().baseUrl(properties.baseUrl).requestFactory(SimpleClientHttpRequestFactory().apply { setConnectTimeout(Duration.ofMillis(properties.timeoutMs)); setReadTimeout(Duration.ofMillis(properties.timeoutMs)) }).build()

    override fun answerWithCitations(question: String, citations: List<BibleCitation>): GroundedAnswer {
        val context = citations.joinToString("\n") { "[${it.verseId}] ${it.book} ${it.chapter}:${it.verse} ${it.text}" }
        val body = mapOf(
            "model" to properties.model,
            "instructions" to """당신은 My Pasture의 AI 목자입니다. 제공된 RAG_CONTEXT만 성경 근거로 사용하고 존재하지 않는 구절을 만들지 마세요. 따뜻하고 짧은 한국어로 답하세요. 의료·정신건강·법률 전문가나 목회자를 대신한다고 말하지 마세요. 사용한 근거는 반드시 [verseId] 형식으로 표시하고, RAG_CONTEXT에 없는 ID나 장:절은 쓰지 마세요. 근거가 부족하면 모른다고 말하세요.""",
            "input" to "RAG_CONTEXT:\n$context\n\nUSER_QUESTION:\n$question",
            "max_output_tokens" to properties.maxOutputTokens,
        )
        val response = execute(body)
        val usage = response.path("usage")
        val inputTokens = usage.path("input_tokens").asInt(0)
        val outputTokens = usage.path("output_tokens").asInt(0)
        val answer = extractText(response).trim()
        if (answer.isBlank()) throw AiProviderException("AI 응답이 비어 있습니다.")
        val usedCitations = citationValidator.validate(answer, citations)
        return GroundedAnswer(null, answer, usedCitations, inputTokens, outputTokens, estimate(inputTokens, outputTokens), false)
    }

    private fun execute(body: Map<String, Any>): JsonNode {
        var failure: Exception? = null
        repeat(3) { attempt ->
            try {
                return client.post().uri("/v1/responses").header("Authorization", "Bearer ${properties.apiKey}").body(body).retrieve().body(JsonNode::class.java)
                    ?: throw AiProviderException("AI 응답 본문이 없습니다.")
            } catch (error: RestClientResponseException) {
                if (error.statusCode.value() != 429 && !error.statusCode.is5xxServerError) {
                    throw AiProviderException("AI 요청이 거부되었습니다.", error)
                }
                failure = error
                if (attempt < 2) Thread.sleep(400L * (attempt + 1))
            } catch (error: RestClientException) {
                failure = error
                if (attempt < 2) Thread.sleep(400L * (attempt + 1))
            }
        }
        throw AiProviderException("AI 서비스에 일시적으로 연결할 수 없습니다.", failure)
    }

    private fun extractText(response: JsonNode): String {
        response.path("output").forEach { output -> output.path("content").forEach { if (it.path("type").asText() == "output_text") return it.path("text").asText() } }
        throw AiProviderException("AI 답변을 해석할 수 없습니다.")
    }

    private fun estimate(input: Int, output: Int): BigDecimal = BigDecimal(input).divide(BigDecimal(1_000_000)).multiply(properties.commentaryInputCostPerMillion).add(BigDecimal(output).divide(BigDecimal(1_000_000)).multiply(properties.commentaryOutputCostPerMillion))
}
