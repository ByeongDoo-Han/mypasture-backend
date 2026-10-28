package com.example.mypasture.ai

import com.example.mypasture.auth.domain.uniqueTestNickname

import com.example.mypasture.config.AiStreamingCoroutineRunner
import com.fasterxml.jackson.databind.ObjectMapper
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint
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

/** JWT 인증된 AiStream 요청이 ASYNC 재-dispatch 뒤에도 정상 완료되는지 검증합니다. */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
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
        AiSseTestSupport.awaitIdle(streamingRunner::isIdle)
    }

    @Test
    fun `인증된 스트림은 변경분 인용 사용량 완료 이벤트를 전송한다`() {
        val email = "ai-sse-${UUID.randomUUID()}@example.com"
        val password = "password123!"
        mockMvc.post("/api/v1/auth/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"$password","displayName":"${uniqueTestNickname()}"}"""
        }.andExpect { status { isCreated() } }
        val login = mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"$password"}"""
        }.andReturn()
        val token = objectMapper.readTree(login.response.contentAsString).path("token").asText()
        val clientRequestId = UUID.randomUUID()

        val initial = mockMvc.post("/api/v1/ai/chat/stream?liveDraft=true") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.TEXT_EVENT_STREAM
            content = """{"question":"창세기 말씀을 설명해주세요","clientRequestId":"$clientRequestId"}"""
        }.andExpect { request { asyncStarted() } }.andReturn()
        awaitStreamCompletion(initial, "done")

        mockMvc.perform(asyncDispatch(initial))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("event:delta")))
            .andExpect(content().string(containsString("event:response")))
            .andExpect(content().string(containsString("assistant-response-v2")))
            .andExpect(content().string(containsString("PRAYER")))
            .andExpect(content().string(containsString("event:citations")))
            .andExpect(content().string(containsString("event:usage")))
            .andExpect(content().string(containsString("event:done")))
        assertTrue(initial.response.contentAsString.indexOf("event:delta") < initial.response.contentAsString.indexOf("event:response"))

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
        awaitStreamCompletion(replay, "done")
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
    fun `인증된 사용량 API는 서버 사용 한도를 반환한다`() {
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
    fun `스트림은 근거 실패 코드를 유지한다`() {
        val token = issueToken("ai-grounding-${UUID.randomUUID()}@example.com")
        val initial = mockMvc.post("/api/v1/ai/chat/stream") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.TEXT_EVENT_STREAM
            content = """{"question":"요한복음 99장 99절을 설명해주세요"}"""
        }.andExpect { request { asyncStarted() } }.andReturn()
        awaitStreamCompletion(initial, "error", "AI_GROUNDING_NOT_FOUND")

        mockMvc.perform(asyncDispatch(initial))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("event:error")))
            .andExpect(content().string(containsString("AI_GROUNDING_NOT_FOUND")))
    }

    @Test
    fun `일반 응답의 범위 오류는 답변 없이 422를 반환한다`() {
        val token = issueToken("ai-scope-http-${UUID.randomUUID()}@example.com")
        for ((question, code) in listOf("비트코인 시세 알려줘" to "AI_QUESTION_OUT_OF_SCOPE", "그냥" to "AI_QUESTION_SCOPE_UNCLEAR")) {
            val id = UUID.randomUUID()
            val initial = mockMvc.post("/api/v1/ai/chat") {
                header("Authorization", "Bearer $token")
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(mapOf("question" to question, "clientRequestId" to id))
            }.andExpect { request { asyncStarted() } }.andReturn()
            initial.getAsyncResult(15_000)
            mockMvc.perform(asyncDispatch(initial)).andExpect(status().isUnprocessableEntity)
                .andExpect(content().string(containsString(code)))
            assertTrue(!initial.response.contentAsString.contains("citations"))
            mockMvc.get("/api/v1/ai/requests/$id") { header("Authorization", "Bearer $token") }.andExpect {
                status { isOk() }; jsonPath("$.status") { value("FAILED") }; jsonPath("$.errorCode") { value(code) }
            }
        }
    }

    @Test
    fun `스트림의 범위 오류는 답변 인용 기도문 변경분 없이 종료한다`() {
        val token = issueToken("ai-scope-sse-${UUID.randomUUID()}@example.com")
        for ((question, code) in listOf("하나님 불안해요 비트코인 시세 알려줘" to "AI_QUESTION_OUT_OF_SCOPE", "그냥" to "AI_QUESTION_SCOPE_UNCLEAR")) {
            val initial = mockMvc.post("/api/v1/ai/chat/stream") {
                header("Authorization", "Bearer $token"); contentType = MediaType.APPLICATION_JSON; accept = MediaType.TEXT_EVENT_STREAM
                content = objectMapper.writeValueAsString(mapOf("question" to question))
            }.andExpect { request { asyncStarted() } }.andReturn()
            awaitStreamCompletion(initial, "error", code)
            mockMvc.perform(asyncDispatch(initial)).andExpect(status().isOk)
                .andExpect(content().string(containsString("event:error")))
            val body = initial.response.contentAsString
            listOf("event:delta", "event:response", "event:citations", "PRAYER").forEach { assertTrue(!body.contains(it), body) }
        }
    }

    private fun issueToken(email: String): String {
        val password = "password123!"
        mockMvc.post("/api/v1/auth/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"$password","displayName":"${uniqueTestNickname()}"}"""
        }.andExpect { status { isCreated() } }
        val login = mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"$password"}"""
        }.andReturn()
        return objectMapper.readTree(login.response.contentAsString).path("token").asText()
    }

    private fun awaitStreamCompletion(result: MvcResult, terminal: String, errorCode: String? = null) {
        val requiredEvents = if (terminal == "done") setOf("delta", "response", "citations", "usage", "done") else setOf("error")
        val frames = AiSseTestSupport.awaitCompletion(result, streamingRunner::isIdle, terminal, requiredEvents)
        if (errorCode != null) {
            kotlin.test.assertEquals(errorCode, objectMapper.readTree(frames.single { it.event == "error" }.data).path("code").asText())
        } else {
            kotlin.test.assertEquals("completed", objectMapper.readTree(frames.single { it.event == "done" }.data).path("status").asText())
        }
    }
}
