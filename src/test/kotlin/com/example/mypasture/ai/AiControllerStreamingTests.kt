package com.example.mypasture.ai

import com.example.mypasture.config.AiStreamingCoroutineRunner
import com.fasterxml.jackson.databind.ObjectMapper
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import kotlin.test.assertTrue

/** JWT 인증된 AI SSE 요청이 ASYNC 재-dispatch 뒤에도 정상 완료되는지 검증합니다. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "app.bible.kor1910-html-path=src/test/resources/bible/kor_html",
        "app.bible.import-enabled=true",
        "app.ai.mock-enabled=true",
        "app.ai.rag-lexical-fallback-enabled=true",
    ],
)
class AiControllerStreamingTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val streamingRunner: AiStreamingCoroutineRunner,
) {
    @AfterEach
    fun awaitStreamingCompletion() {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline && !streamingRunner.isIdle()) {
            Thread.sleep(10)
        }
        assertTrue(streamingRunner.isIdle(), "AI streaming coroutine did not finish within 5 seconds")
    }

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
        val clientRequestId = UUID.randomUUID()

        val initial = mockMvc.post("/api/v1/ai/chat/stream") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.TEXT_EVENT_STREAM
            content = """{"question":"창세기 말씀을 설명해주세요","clientRequestId":"$clientRequestId"}"""
        }.andExpect { request { asyncStarted() } }.andReturn()
        initial.getAsyncResult(5_000)
        awaitStreamContent(initial, "event:done")

        mockMvc.perform(asyncDispatch(initial))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("event:delta")))
            .andExpect(content().string(containsString("event:response")))
            .andExpect(content().string(containsString("assistant-response-v2")))
            .andExpect(content().string(containsString("PRAYER")))
            .andExpect(content().string(containsString("event:citations")))
            .andExpect(content().string(containsString("event:usage")))
            .andExpect(content().string(containsString("event:done")))

        mockMvc.get("/api/v1/ai/requests/$clientRequestId") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.clientRequestId") { value(clientRequestId.toString()) }
            jsonPath("$.status") { value("COMPLETED") }
            jsonPath("$.response.status") { value("COMPLETED") }
        }

        val strangerToken = issueToken("ai-sse-stranger-${UUID.randomUUID()}@example.com")
        mockMvc.get("/api/v1/ai/requests/$clientRequestId") {
            header("Authorization", "Bearer $strangerToken")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("AI_REQUEST_NOT_FOUND") }
        }

        val replay = mockMvc.post("/api/v1/ai/chat/stream") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.TEXT_EVENT_STREAM
            content = """{"question":"창세기 말씀을 설명해주세요","clientRequestId":"$clientRequestId"}"""
        }.andExpect { request { asyncStarted() } }.andReturn()
        replay.getAsyncResult(5_000)
        awaitStreamContent(replay, "event:done")
        mockMvc.perform(asyncDispatch(replay))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("event:delta")))

        mockMvc.get("/api/v1/ai/usage/today") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.used") { value(1) }
        }
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
        initial.getAsyncResult(5_000)
        awaitStreamContent(initial, "AI_GROUNDING_NOT_FOUND")

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

    /** SseEmitter 반환과 실제 마지막 event 기록 사이의 race를 제거합니다. */
    private fun awaitStreamContent(result: MvcResult, marker: String) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            if (result.response.contentAsString.contains(marker)) return
            Thread.sleep(10)
        }
        error("SSE response did not contain '$marker' within 5 seconds")
    }
}
