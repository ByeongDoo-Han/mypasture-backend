package com.example.mypasture.ai.infrastructure

import com.example.mypasture.ai.application.AiProviderException
import com.example.mypasture.ai.application.TextEmbeddingPort
import com.example.mypasture.ai.domain.TextEmbeddingBatch
import com.example.mypasture.config.AiProperties
import com.fasterxml.jackson.databind.JsonNode
import java.math.BigDecimal
import java.time.Duration
import kotlin.math.sqrt
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException

/** 운영 환경에서 OpenAI Embeddings API를 호출하고 응답 순서와 vector 차원을 검증합니다. */
@Component
@ConditionalOnProperty(prefix = "app.ai", name = ["mock-enabled"], havingValue = "false", matchIfMissing = true)
class OpenAiTextEmbeddingAdapter(
    private val properties: AiProperties,
) : TextEmbeddingPort {
    private val client = RestClient.builder()
        .baseUrl(properties.baseUrl)
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(Duration.ofMillis(properties.timeoutMs))
                setReadTimeout(Duration.ofMillis(properties.timeoutMs))
            },
        )
        .build()

    override fun embed(inputs: List<String>): TextEmbeddingBatch {
        require(inputs.isNotEmpty()) { "Embedding inputs must not be empty." }
        require(inputs.none { it.isBlank() }) { "Embedding input must not be blank." }

        val response = execute(
            mapOf(
                "model" to properties.embeddingModel,
                "input" to inputs,
                "dimensions" to properties.embeddingDimensions,
                "encoding_format" to "float",
            ),
        )
        val vectors = parseVectors(response, inputs.size)
        val promptTokens = response.path("usage").path("prompt_tokens").asInt(0)
        return TextEmbeddingBatch(
            vectors = vectors,
            promptTokens = promptTokens,
            estimatedCostUsd = BigDecimal(promptTokens)
                .movePointLeft(6)
                .multiply(properties.embeddingInputCostPerMillion),
        )
    }

    private fun execute(body: Map<String, Any>): JsonNode {
        var failure: Exception? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                return client.post()
                    .uri("/v1/embeddings")
                    .header("Authorization", "Bearer ${properties.apiKey}")
                    .body(body)
                    .retrieve()
                    .body(JsonNode::class.java)
                    ?: throw AiProviderException("Embedding 응답 본문이 없습니다.")
            } catch (error: RestClientResponseException) {
                if (error.statusCode.value() != 429 && !error.statusCode.is5xxServerError) {
                    throw AiProviderException("Embedding 요청이 거부되었습니다.", error)
                }
                failure = error
                if (attempt < MAX_ATTEMPTS - 1) Thread.sleep(RETRY_DELAY_MS * (attempt + 1))
            } catch (error: RestClientException) {
                failure = error
                if (attempt < MAX_ATTEMPTS - 1) Thread.sleep(RETRY_DELAY_MS * (attempt + 1))
            }
        }
        throw AiProviderException("Embedding 서비스에 일시적으로 연결할 수 없습니다.", failure)
    }

    private fun parseVectors(response: JsonNode, expectedCount: Int): List<FloatArray> {
        val indexed = response.path("data").associate { item ->
            item.path("index").asInt(-1) to item.path("embedding").map(JsonNode::floatValue).toFloatArray()
        }
        require(indexed.keys == (0 until expectedCount).toSet()) { "Embedding response indices do not match inputs." }
        return (0 until expectedCount).map { index ->
            indexed.getValue(index).also { vector ->
                require(vector.size == properties.embeddingDimensions) {
                    "Embedding dimensions must be ${properties.embeddingDimensions}, but were ${vector.size}."
                }
                require(vector.all(Float::isFinite)) { "Embedding contains a non-finite value." }
            }
        }
    }

    companion object {
        private const val MAX_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 400L
    }
}

/** 로컬 개발에서 외부 비용 없이 검색 흐름을 재현하는 결정적 hash embedding 구현체입니다. */
@Component
@ConditionalOnProperty(prefix = "app.ai", name = ["mock-enabled"], havingValue = "true")
class LocalTextEmbeddingAdapter(
    private val properties: AiProperties,
) : TextEmbeddingPort {
    override fun embed(inputs: List<String>): TextEmbeddingBatch {
        require(inputs.isNotEmpty()) { "Embedding inputs must not be empty." }
        val vectors = inputs.map(::embedOne)
        return TextEmbeddingBatch(
            vectors = vectors,
            promptTokens = inputs.sumOf(::estimateTokens),
            estimatedCostUsd = BigDecimal.ZERO,
        )
    }

    private fun embedOne(input: String): FloatArray {
        val vector = FloatArray(properties.embeddingDimensions)
        val normalized = input.trim().lowercase()
        val features = buildList {
            WORD_REGEX.findAll(normalized).forEach { add(it.value) }
            normalized.windowed(size = 2, step = 1, partialWindows = false).forEach(::add)
        }
        features.ifEmpty { listOf(normalized) }.forEach { feature ->
            val hash = feature.hashCode()
            val index = (hash and Int.MAX_VALUE) % vector.size
            vector[index] += if (hash and 1 == 0) 1f else -1f
        }
        val norm = sqrt(vector.sumOf { value -> value.toDouble() * value })
        if (norm > 0.0) vector.indices.forEach { vector[it] = (vector[it] / norm).toFloat() }
        return vector
    }

    private fun estimateTokens(input: String): Int = (input.length / 3).coerceAtLeast(1)

    companion object {
        private val WORD_REGEX = Regex("[가-힣A-Za-z0-9]+")
    }
}
