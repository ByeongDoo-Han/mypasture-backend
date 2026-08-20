package com.example.mypasture.ai

import com.fasterxml.jackson.databind.ObjectMapper
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/** JWT 인증된 AI SSE 요청이 ASYNC 재-dispatch 뒤에도 정상 완료되는지 검증합니다. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "app.bible.kor1910-html-path=src/test/resources/bible/kor_html",
        "app.bible.import-enabled=true",
        "app.ai.mock-enabled=true",
    ],
)
class AiControllerStreamingTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
) {
    @Test
    fun `authenticated stream completes with delta citations usage and done events`() {
        val email = "ai-sse-${UUID.randomUUID()}@example.com"
        val password = "password123!"
        mockMvc.post("/api/v1/auth/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"$password","displayName":"AI SSE"}"""
        }.andExpect { status { isCreated() } }
        val login = mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"$password"}"""
        }.andReturn()
        val token = objectMapper.readTree(login.response.contentAsString).path("token").asText()

        val initial = mockMvc.post("/api/v1/ai/chat/stream") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.TEXT_EVENT_STREAM
            content = """{"question":"창세기 말씀을 설명해주세요"}"""
        }.andExpect { request { asyncStarted() } }.andReturn()

        mockMvc.perform(asyncDispatch(initial))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("event:delta")))
            .andExpect(content().string(containsString("event:citations")))
            .andExpect(content().string(containsString("event:usage")))
            .andExpect(content().string(containsString("event:done")))
    }

    @Test
    fun `authenticated usage endpoint reports server quota`() {
        val token = issueToken("ai-usage-${UUID.randomUUID()}@example.com")

        mockMvc.get("/api/v1/ai/usage/today") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.limit") { value(10) }
            jsonPath("$.used") { value(0) }
            jsonPath("$.remaining") { value(10) }
            jsonPath("$.unlimited") { value(false) }
        }
    }

    @Test
    fun `stream preserves grounding failure code`() {
        val token = issueToken("ai-grounding-${UUID.randomUUID()}@example.com")
        val initial = mockMvc.post("/api/v1/ai/chat/stream") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.TEXT_EVENT_STREAM
            content = """{"question":"zxqvnoresulttoken"}"""
        }.andExpect { request { asyncStarted() } }.andReturn()

        mockMvc.perform(asyncDispatch(initial))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("event:error")))
            .andExpect(content().string(containsString("AI_GROUNDING_NOT_FOUND")))
    }

    private fun issueToken(email: String): String {
        val password = "password123!"
        mockMvc.post("/api/v1/auth/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"$password","displayName":"AI Test"}"""
        }.andExpect { status { isCreated() } }
        val login = mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"$password"}"""
        }.andReturn()
        return objectMapper.readTree(login.response.contentAsString).path("token").asText()
    }
}
