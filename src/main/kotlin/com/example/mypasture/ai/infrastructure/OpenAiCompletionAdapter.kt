package com.example.mypasture.ai.infrastructure

import com.example.mypasture.ai.application.AiCompletionPort
import com.example.mypasture.ai.application.AiProviderException
import com.example.mypasture.ai.application.AiStreamingCompletionPort
import com.example.mypasture.ai.domain.BibleCitation
import com.example.mypasture.ai.domain.GroundedAnswer
import com.example.mypasture.config.AiProperties
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import java.math.BigDecimal
import java.time.Duration
import java.io.BufferedReader

/** 운영 환경에서 OpenAI Responses API를 호출하고 RAG로 검색한 성경 구절만 근거로 전달합니다. */
@Component
@ConditionalOnProperty(prefix = "app.ai", name = ["mock-enabled"], havingValue = "false", matchIfMissing = true)
class OpenAiCompletionAdapter(
    private val properties: AiProperties,
    private val citationValidator: AiCitationValidator,
    private val objectMapper: ObjectMapper,
) : AiCompletionPort, AiStreamingCompletionPort {
    private val client = createClient(properties.timeoutMs)
    private val streamingClient = createClient(properties.streamTimeoutMs)

    override fun answerWithCitations(question: String, citations: List<BibleCitation>): GroundedAnswer {
        val response = execute(requestBody(question, citations, streaming = false))
        return toGroundedAnswer(response, citations)
    }

    /** Responses API의 output_text delta를 수신하는 즉시 application callback으로 전달합니다. */
    override fun answerWithCitations(
        question: String,
        citations: List<BibleCitation>,
        onDelta: (String) -> Unit,
    ): GroundedAnswer {
        val guard = StreamingCitationGuard(citations, citationValidator, onDelta)
        val response = executeStreaming(requestBody(question, citations, streaming = true)) { delta ->
            guard.accept(delta)
        }
        return toGroundedAnswer(response, citations, guard.finish())
    }

    private fun requestBody(question: String, citations: List<BibleCitation>, streaming: Boolean): Map<String, Any> {
        val context = citations.joinToString("\n") { "[${it.verseId}] ${it.book} ${it.chapter}:${it.verse} ${it.text}" }
        return mapOf(
            "model" to properties.model,
            "instructions" to """당신은 My Pasture의 AI 목자입니다. 제공된 RAG_CONTEXT만 성경 근거로 사용하고 존재하지 않는 구절을 만들지 마세요. 답변은 반드시 '근거: [verseId]'로 시작하고, 따뜻하고 짧은 한국어로 답하세요. 의료·정신건강·법률 전문가나 목회자를 대신한다고 말하지 마세요. 사용한 근거는 반드시 [verseId] 형식으로 표시하고, RAG_CONTEXT에 없는 ID나 장:절은 쓰지 마세요. 근거가 부족하면 모른다고 말하세요.""",
            "input" to "RAG_CONTEXT:\n$context\n\nUSER_QUESTION:\n$question",
            "max_output_tokens" to properties.maxOutputTokens,
            "stream" to streaming,
        )
    }

    private fun toGroundedAnswer(response: JsonNode, citations: List<BibleCitation>, streamedText: String? = null): GroundedAnswer {
        val usage = response.path("usage")
        val inputTokens = usage.path("input_tokens").asInt(0)
        val outputTokens = usage.path("output_tokens").asInt(0)
        val answer = (streamedText?.takeIf { it.isNotBlank() } ?: extractText(response)).trim()
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

    private fun executeStreaming(body: Map<String, Any>, onDelta: (String) -> Unit): JsonNode {
        var failure: Exception? = null
        var emitted = false
        repeat(3) { attempt ->
            try {
                return streamingClient.post()
                    .uri("/v1/responses")
                    .header("Authorization", "Bearer ${properties.apiKey}")
                    .body(body)
                    .exchange { _, response ->
                        if (!response.statusCode.is2xxSuccessful) {
                            throw StreamingRequestException(response.statusCode.value() == 429 || response.statusCode.is5xxServerError)
                        }
                        parseStreamingResponse(response.body.bufferedReader()) { delta ->
                            emitted = true
                            onDelta(delta)
                        }
                    }
            } catch (error: StreamingRequestException) {
                if (!error.retryable || emitted) throw AiProviderException("AI streaming 요청이 거부되었습니다.", error)
                failure = error
            } catch (error: RestClientException) {
                if (emitted) throw AiProviderException("AI streaming 연결이 중단되었습니다.", error)
                failure = error
            }
            if (attempt < 2) Thread.sleep(400L * (attempt + 1))
        }
        throw AiProviderException("AI streaming 서비스에 일시적으로 연결할 수 없습니다.", failure)
    }

    private fun parseStreamingResponse(reader: BufferedReader, onDelta: (String) -> Unit): JsonNode {
        var completedResponse: JsonNode? = null
        reader.useLines { lines ->
            lines.filter { it.startsWith("data:") }
                .map { it.removePrefix("data:").trim() }
                .filter { it.isNotBlank() && it != "[DONE]" }
                .forEach { data ->
                    val event = objectMapper.readTree(data)
                    when (event.path("type").asText()) {
                        "response.output_text.delta" -> event.path("delta").asText().takeIf { it.isNotEmpty() }?.let(onDelta)
                        "response.completed" -> completedResponse = event.path("response")
                        "response.failed", "response.incomplete", "error" -> {
                            val message = event.path("response").path("error").path("message").asText()
                                .ifBlank { event.path("error").path("message").asText() }
                                .ifBlank { "AI streaming 응답이 완료되지 않았습니다." }
                            throw AiProviderException(message)
                        }
                    }
                }
        }
        return completedResponse ?: throw AiProviderException("AI streaming 완료 이벤트를 받지 못했습니다.")
    }

    private fun extractText(response: JsonNode): String {
        response.path("output").forEach { output -> output.path("content").forEach { if (it.path("type").asText() == "output_text") return it.path("text").asText() } }
        throw AiProviderException("AI 답변을 해석할 수 없습니다.")
    }

    private fun estimate(input: Int, output: Int): BigDecimal = BigDecimal(input).divide(BigDecimal(1_000_000)).multiply(properties.commentaryInputCostPerMillion).add(BigDecimal(output).divide(BigDecimal(1_000_000)).multiply(properties.commentaryOutputCostPerMillion))

    private fun createClient(readTimeoutMs: Long): RestClient =
        RestClient.builder()
            .baseUrl(properties.baseUrl)
            .requestFactory(
                SimpleClientHttpRequestFactory().apply {
                    setConnectTimeout(Duration.ofMillis(properties.timeoutMs))
                    setReadTimeout(Duration.ofMillis(readTimeoutMs))
                },
            )
            .build()

    /** HTTP 상태에 따라 delta 전 재시도 가능 여부를 보존하는 내부 예외입니다. */
    private class StreamingRequestException(val retryable: Boolean) : RuntimeException()

    /** 완성되지 않은 인용 표기를 보류하고 검증된 RAG 인용이 나온 뒤에만 delta를 공개합니다. */
    private class StreamingCitationGuard(
        private val citations: List<BibleCitation>,
        private val validator: AiCitationValidator,
        private val emit: (String) -> Unit,
    ) {
        private val fullText = StringBuilder()
        private val pending = StringBuilder()
        private val allowedIds = citations.map { it.verseId }.toSet()
        private var grounded = false

        fun accept(delta: String) {
            fullText.append(delta)
            pending.append(delta)
            val safeEnd = safeEnd(pending.toString())
            if (safeEnd == 0) return

            val candidate = pending.substring(0, safeEnd)
            validator.validateReferences(candidate, citations)
            if (!grounded) {
                grounded = CITATION_ID_REGEX.findAll(candidate).any { it.groupValues[1].toLong() in allowedIds }
                if (!grounded) return
            }
            emit(candidate)
            pending.delete(0, safeEnd)
        }

        fun finish(): String {
            val answer = fullText.toString()
            validator.validate(answer, citations)
            if (pending.isNotEmpty()) {
                validator.validateReferences(pending.toString(), citations)
                emit(pending.toString())
                pending.clear()
            }
            return answer
        }

        private fun safeEnd(value: String): Int {
            val unmatchedBracket = value.lastIndexOf('[').takeIf { it > value.lastIndexOf(']') }
            val trailingReference = TRAILING_REFERENCE.find(value)?.range?.first
            return listOfNotNull(unmatchedBracket, trailingReference).minOrNull() ?: value.length
        }

        companion object {
            private val CITATION_ID_REGEX = Regex("\\[(\\d+)]")
            private val TRAILING_REFERENCE = Regex("\\d{1,3}(?::\\d{0,3})?$")
        }
    }
}
