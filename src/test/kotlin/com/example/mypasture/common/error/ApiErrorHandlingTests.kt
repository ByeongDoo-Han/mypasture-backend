package com.example.mypasture.common.error

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID

/** 공통 오류 응답이 MVC, 보안 필터, 도메인 경계에서 같은 계약을 유지하는지 검증합니다. */
@SpringBootTest
@AutoConfigureMockMvc
class ApiErrorHandlingTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
) {
    @Test
    fun `validation errors include fields and request trace`() {
        mockMvc.post("/api/v1/auth/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"invalid","password":"short","displayName":""}"""
        }.andExpect {
            status { isBadRequest() }
            header { exists(RequestCorrelationFilter.TRACE_ID_HEADER) }
            jsonPath("$.code") { value("INVALID_REQUEST") }
            jsonPath("$.path") { value("/api/v1/auth/signup") }
            jsonPath("$.traceId") { exists() }
            jsonPath("$.timestamp") { exists() }
            jsonPath("$.fieldErrors[?(@.field == 'email')]") { exists() }
            jsonPath("$.fieldErrors[?(@.field == 'password')]") { exists() }
            jsonPath("$.fieldErrors[?(@.field == 'displayName')]") { exists() }
        }
    }

    @Test
    fun `malformed json uses common error contract`() {
        mockMvc.post("/api/v1/auth/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("MALFORMED_REQUEST") }
            jsonPath("$.path") { value("/api/v1/auth/signup") }
            jsonPath("$.traceId") { exists() }
            jsonPath("$.fieldErrors.length()") { value(0) }
        }
    }

    @Test
    fun `security error body and response header share trace id`() {
        val result = mockMvc.get("/api/v1/auth/me")
            .andExpect {
                status { isUnauthorized() }
                jsonPath("$.code") { value("UNAUTHORIZED") }
                jsonPath("$.path") { value("/api/v1/auth/me") }
                jsonPath("$.traceId") { exists() }
            }
            .andReturn()

        val body = objectMapper.readTree(result.response.contentAsString)
        assertEquals(
            result.response.getHeader(RequestCorrelationFilter.TRACE_ID_HEADER),
            body["traceId"].asText(),
        )
    }

    @Test
    fun `authenticated user without admin role receives common forbidden response`() {
        val email = "error-contract-${UUID.randomUUID()}@example.com"
        mockMvc.post("/api/v1/auth/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"password123!","displayName":"Error Contract"}"""
        }.andExpect {
            status { isCreated() }
        }
        val login = mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"password123!"}"""
        }.andReturn()
        val accessToken = objectMapper.readTree(login.response.contentAsString)["token"].asText()

        mockMvc.get("/api/v1/admin/ai/embeddings") {
            header("Authorization", "Bearer $accessToken")
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("ACCESS_DENIED") }
            jsonPath("$.path") { value("/api/v1/admin/ai/embeddings") }
            jsonPath("$.traceId") { exists() }
        }
    }

    @Test
    fun `unknown public route returns common not found response`() {
        mockMvc.get("/api/v1/bibles/unknown/public-route")
            .andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value("RESOURCE_NOT_FOUND") }
                jsonPath("$.path") { value("/api/v1/bibles/unknown/public-route") }
                jsonPath("$.traceId") { exists() }
            }
    }
}
