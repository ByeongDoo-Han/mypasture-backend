package com.example.mypasture.config

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/** 실제 OpenAPI JSON에서 모든 공개 계약의 설명과 입력 파라미터 설명을 확인합니다. */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiDescriptionTests(
    @Autowired private val mvc: MockMvc,
    @Autowired private val json: ObjectMapper,
) {
    @Test
    fun `every documented operation and path or query parameter has a description`() {
        val body = mvc.get("/v3/api-docs").andExpect { status { isOk() } }.andReturn().response.contentAsString
        val paths = json.readTree(body)["paths"]
        val methods = setOf("get", "post", "put", "patch", "delete")
        var operationCount = 0
        val missing = mutableListOf<String>()

        paths.fields().forEach { (path, pathItem) ->
            pathItem.fields().forEach operationLoop@{ (method, operation) ->
                if (method !in methods) return@operationLoop
                operationCount++
                if (operation.path("summary").asText().isBlank() || operation.path("description").asText().isBlank()) {
                    missing += "$method $path: operation summary or description"
                }
                if (operation.path("tags").isEmpty) missing += "$method $path: tag"
                operation.path("parameters").forEach { parameter ->
                    if (parameter.path("description").asText().isBlank()) {
                        missing += "$method $path: ${parameter.path("name").asText()} parameter description"
                    }
                }
            }
        }

        assertTrue(operationCount >= 161, "Expected all API contracts, found $operationCount operations")
        assertTrue(missing.isEmpty(), missing.joinToString("\n"))
    }
}
