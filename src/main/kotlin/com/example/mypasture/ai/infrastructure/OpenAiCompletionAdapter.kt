package com.example.mypasture.ai.infrastructure

import com.example.mypasture.ai.application.AiCompletionPort
import com.example.mypasture.ai.application.AiProviderException
import com.example.mypasture.ai.application.AiStreamingCompletionPort
import com.example.mypasture.ai.domain.BibleCitation
import com.example.mypasture.ai.domain.GroundedAnswer
import com.example.mypasture.config.AiProperties
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.netty.channel.ChannelOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.reactive.asFlow
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.awaitBody
import reactor.core.publisher.Flux
import reactor.netty.http.client.HttpClient
import java.math.BigDecimal
import java.time.Duration

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

    override suspend fun answerWithCitations(question: String, citations: List<BibleCitation>): GroundedAnswer {
        val response = execute(requestBody(question, citations, streaming = false))
        return toGroundedAnswer(response, citations)
    }

    /** Responses API의 output_text delta를 수신하는 즉시 application callback으로 전달합니다. */
    override suspend fun answerWithCitations(
        question: String,
        citations: List<BibleCitation>,
        onDelta: suspend (String) -> Unit,
    ): GroundedAnswer {
        val guard = StreamingCitationGuard(citations, citationValidator, onDelta)
        val response = executeStreaming(requestBody(question, citations, streaming = true)) { delta ->
            guard.accept(delta)
        }
        return toGroundedAnswer(response, citations, guard.finish())
    }

    private fun requestBody(question: String, citations: List<BibleCitation>, streaming: Boolean): Map<String, Any> {
        val context = citations.joinToString("\n") { "[${it.verseId}] ${it.book} ${it.chapter}:${it.verse} ${it.text}" }
        return buildMap {
            put("model", properties.model)
            put("instructions", """당신은 My Pasture의 AI 목자입니다. 제공된 RAG_CONTEXT만 성경 근거로 사용하고 존재하지 않는 구절을 만들지 마세요. 답변은 반드시 '근거: [verseId]'로 시작하고, 따뜻하고 짧은 한국어로 답하세요. 의료·정신건강·법률 전문가나 목회자를 대신한다고 말하지 마세요. 사용한 근거는 반드시 [verseId] 형식으로 표시하고, RAG_CONTEXT에 없는 ID나 장:절은 쓰지 마세요. 근거가 부족하면 모른다고 말하세요.""")
            put("input", "RAG_CONTEXT:\n$context\n\nUSER_QUESTION:\n$question")
            put("max_output_tokens", properties.maxOutputTokens)
            // 목회 상담 성격의 질문이 Responses API application state로 보존되지 않게 합니다.
            put("store", false)
            put("stream", streaming)
            if (properties.model.startsWith("gpt-5", ignoreCase = true)) {
                put("reasoning", mapOf("effort" to properties.reasoningEffort))
            }
        }
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

    private suspend fun execute(body: Map<String, Any>): JsonNode {
        var failure: Exception? = null
        repeat(3) { attempt ->
            try {
                return client.post()
                    .uri("/v1/responses")
                    .header("Authorization", "Bearer ${properties.apiKey}")
                    .bodyValue(body)
                    .retrieve()
                    .awaitBody()
            } catch (error: WebClientResponseException) {
                if (error.statusCode.value() != 429 && !error.statusCode.is5xxServerError) {
                    throw AiProviderException("AI 요청이 거부되었습니다.", error)
                }
                failure = error
                if (attempt < 2) delay(400L * (attempt + 1))
            } catch (error: WebClientRequestException) {
                failure = error
                if (attempt < 2) delay(400L * (attempt + 1))
            } catch (error: CancellationException) {
                throw error
            }
        }
        throw AiProviderException("AI 서비스에 일시적으로 연결할 수 없습니다.", failure)
    }

    private suspend fun executeStreaming(body: Map<String, Any>, onDelta: suspend (String) -> Unit): JsonNode {
        var failure: Exception? = null
        var emitted = false
        repeat(3) { attempt ->
            try {
                var completedResponse: JsonNode? = null
                streamingClient.post()
                    .uri("/v1/responses")
                    .header("Authorization", "Bearer ${properties.apiKey}")
                    .bodyValue(body)
                    .exchangeToFlux { response ->
                        if (!response.statusCode().is2xxSuccessful) {
                            Flux.error<ServerSentEvent<String>>(
                                StreamingRequestException(
                                    response.statusCode().value() == 429 || response.statusCode().is5xxServerError,
                                ),
                            )
                        } else {
                            response.bodyToFlux(SSE_TYPE)
                        }
                    }
                    .asFlow()
                    .collect { event ->
                        val data = event.data()?.trim().orEmpty()
                        if (data.isBlank() || data == "[DONE]") return@collect
                        val payload = objectMapper.readTree(data)
                        when (payload.path("type").asText()) {
                            "response.output_text.delta" -> payload.path("delta").asText().takeIf { it.isNotEmpty() }?.let {
                                emitted = true
                                onDelta(it)
                            }
                            "response.completed" -> completedResponse = payload.path("response")
                            "response.failed", "response.incomplete", "error" -> {
                                throw AiProviderException("AI streaming 응답이 완료되지 않았습니다.")
                            }
                        }
                    }
                return completedResponse ?: throw AiProviderException("AI streaming 완료 이벤트를 받지 못했습니다.")
            } catch (error: StreamingRequestException) {
                if (!error.retryable || emitted) throw AiProviderException("AI streaming 요청이 거부되었습니다.", error)
                failure = error
            } catch (error: WebClientRequestException) {
                if (emitted) throw AiProviderException("AI streaming 연결이 중단되었습니다.", error)
                failure = error
            } catch (error: CancellationException) {
                throw error
            }
            if (attempt < 2) delay(400L * (attempt + 1))
        }
        throw AiProviderException("AI streaming 서비스에 일시적으로 연결할 수 없습니다.", failure)
    }

    private fun extractText(response: JsonNode): String {
        response.path("output").forEach { output -> output.path("content").forEach { if (it.path("type").asText() == "output_text") return it.path("text").asText() } }
        throw AiProviderException("AI 답변을 해석할 수 없습니다.")
    }

    private fun estimate(input: Int, output: Int): BigDecimal = BigDecimal(input).divide(BigDecimal(1_000_000)).multiply(properties.commentaryInputCostPerMillion).add(BigDecimal(output).divide(BigDecimal(1_000_000)).multiply(properties.commentaryOutputCostPerMillion))

    private fun createClient(readTimeoutMs: Long): WebClient {
        val httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.timeoutMs.toInt())
            .responseTimeout(Duration.ofMillis(readTimeoutMs))
        return WebClient.builder()
            .baseUrl(properties.baseUrl)
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .build()
    }

    /** HTTP 상태에 따라 delta 전 재시도 가능 여부를 보존하는 내부 예외입니다. */
    private class StreamingRequestException(val retryable: Boolean) : RuntimeException()

    /** 완성되지 않은 인용 표기를 보류하고 검증된 RAG 인용이 나온 뒤에만 delta를 공개합니다. */
    private class StreamingCitationGuard(
        private val citations: List<BibleCitation>,
        private val validator: AiCitationValidator,
        private val emit: suspend (String) -> Unit,
    ) {
        private val fullText = StringBuilder()
        private val pending = StringBuilder()
        private val allowedIds = citations.map { it.verseId }.toSet()
        private var grounded = false

        suspend fun accept(delta: String) {
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

        suspend fun finish(): String {
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

    companion object {
        private val SSE_TYPE = object : ParameterizedTypeReference<ServerSentEvent<String>>() {}
    }
}
