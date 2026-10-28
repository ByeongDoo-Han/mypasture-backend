package com.example.mypasture.ai

import com.example.mypasture.auth.domain.uniqueTestNickname

import com.example.mypasture.ai.application.AskBibleAssistantUseCase
import com.example.mypasture.auth.infrastructure.UserRepository
import com.example.mypasture.user.domain.SubscriptionTier
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import com.example.mypasture.ai.presentation.AiHistoryExceptionHandler
import com.example.mypasture.common.error.RequestCorrelationFilter
import org.springframework.mock.web.MockHttpServletRequest
import java.time.Instant
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import jakarta.servlet.Filter
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.WriteListener
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpServletResponseWrapper
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

/** HTTP 계층이 본인 JWT와 no-store, 404 소유권 규칙을 지키는지 검증합니다. */
@SpringBootTest
@Import(AiHistoryTestConfiguration::class)
@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "app.bible.kor1910-html-path=src/test/resources/bible/kor_html",
        "app.bible.import-enabled=true",
        "app.ai.mock-enabled=true",
        "app.ai.rag-lexical-fallback-enabled=true",
    ],
)
class AiHistoryControllerTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val mapper: ObjectMapper,
    @Autowired private val users: UserRepository,
    @Autowired private val assistant: AskBibleAssistantUseCase,
    @Autowired private val context: WebApplicationContext,
    @Autowired private val securityFilters: org.springframework.security.web.FilterChainProxy,
    @Autowired private val streamingRunner: com.example.mypasture.config.AiStreamingCoroutineRunner,
) {
    @Test
    fun `답변 기록은 인증이 필요하고 다른 사용자 기록을 숨긴다`() {
        val ownerEmail = "history-http-${UUID.randomUUID()}@example.com"
        val otherEmail = "history-http-${UUID.randomUUID()}@example.com"
        val ownerToken = issueToken(ownerEmail)
        val otherToken = issueToken(otherEmail)
        val owner = users.findByEmailAndDeletedAtIsNull(ownerEmail)!!
        val answer = assistant.ask(owner.id, SubscriptionTier.FREE, "창세기 1장 1절 설명 ${UUID.randomUUID()}")

        mockMvc.get("/api/v1/ai/history").andExpect { status { isUnauthorized() } }
        mockMvc.get("/api/v1/ai/history") {
            header("Authorization", "Bearer $ownerToken")
        }.andExpect {
            status { isOk() }
            header { string("Cache-Control", "no-store") }
            jsonPath("$.items[0].turnId") { value(answer.turnId.toString()) }
            jsonPath("$.items[0].assistantVersion") { value(com.example.mypasture.ai.domain.AI_PASTOR_VERSION) }
        }
        val detail = mockMvc.get("/api/v1/ai/history/${answer.turnId}") {
            header("Authorization", "Bearer $ownerToken")
        }.andExpect {
            status { isOk() }
            header { string("Cache-Control", "no-store") }
            jsonPath("$.versions[0].kind") { value("ORIGINAL") }
        }.andReturn()
        assertEquals(answer.answer, mapper.readTree(detail.response.contentAsString).path("versions")[0].path("answer").asText())

        mockMvc.get("/api/v1/ai/history/${answer.turnId}") {
            header("Authorization", "Bearer $otherToken")
        }.andExpect { status { isNotFound() }; header { string("Cache-Control", "no-store") } }
        mockMvc.delete("/api/v1/ai/history/${answer.turnId}") {
            header("Authorization", "Bearer $otherToken")
        }.andExpect { status { isNoContent() } }
        mockMvc.get("/api/v1/ai/history/${answer.turnId}") {
            header("Authorization", "Bearer $ownerToken")
        }.andExpect { status { isOk() } }
        mockMvc.delete("/api/v1/ai/history/${answer.turnId}") {
            header("Authorization", "Bearer $ownerToken")
        }.andExpect { status { isNoContent() }; header { string("Cache-Control", "no-store") } }
        mockMvc.delete("/api/v1/ai/history/${answer.turnId}") {
            header("Authorization", "Bearer $ownerToken")
        }.andExpect { status { isNoContent() } }
        mockMvc.get("/api/v1/ai/history/${answer.turnId}") {
            header("Authorization", "Bearer $ownerToken")
        }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `잘못된 커서와 크기는 캐시 금지 400 응답을 반환한다`() {
        val token = issueToken("history-http-${UUID.randomUUID()}@example.com")
        mockMvc.get("/api/v1/ai/history?size=0") {
            header("Authorization", "Bearer $token")
        }.andExpect { status { isBadRequest() }; header { string("Cache-Control", "no-store") } }
        for (path in listOf("/api/v1/ai/history?size=invalid", "/api/v1/ai/history/invalid-uuid")) {
            mockMvc.get(path) { header("Authorization", "Bearer $token") }
                .andExpect { status { isBadRequest() }; header { string("Cache-Control", "no-store") } }
        }
        val error = mockMvc.get("/api/v1/ai/history?cursor=bad") {
            header("Authorization", "Bearer $token")
        }.andExpect { status { isBadRequest() }; header { string("Cache-Control", "no-store") } }.andReturn()
        assertInvalidCursorContract(error.response.contentAsString, error.response.getHeader(RequestCorrelationFilter.TRACE_ID_HEADER)!!)
    }

    @Test
    fun `커서 오류 응답은 추적 ID의 bad는 허용하고 커서 값 노출은 막는다`() {
        // 유효한 UUID에 cursor와 같은 substring이 있어도 추적 메타데이터는 누출이 아닙니다.
        val traceId = "00000bad-0000-4000-8000-000000000000"
        val request = MockHttpServletRequest("GET", "/api/v1/ai/history").apply {
            queryString = "cursor=bad"
            setAttribute(RequestCorrelationFilter.TRACE_ID_ATTRIBUTE, traceId)
        }
        val response = mapper.writeValueAsString(AiHistoryExceptionHandler().invalidRequest(request).body)
        assertTrue(response.contains("bad"), "The deterministic collision must exercise the former assertion")
        assertInvalidCursorContract(response, traceId)
        for (field in listOf("code", "message", "path")) {
            val leaked = mapper.readTree(response).deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
            leaked.put(field, leaked.path(field).asText() + " cursor=bad")
            assertThrows(AssertionError::class.java) { assertInvalidCursorContract(mapper.writeValueAsString(leaked), traceId) }
        }
        val leakedFields = mapper.readTree(response).deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()
        leakedFields.putArray("fieldErrors").addObject().put("field", "cursor").put("message", "bad")
        assertThrows(AssertionError::class.java) { assertInvalidCursorContract(mapper.writeValueAsString(leakedFields), traceId) }
    }

    private fun assertInvalidCursorContract(body: String, traceId: String) {
        val error = mapper.readTree(body)
        assertEquals(setOf("code", "message", "timestamp", "path", "traceId", "fieldErrors"), error.fieldNames().asSequence().toSet())
        assertEquals("AI_HISTORY_INVALID_REQUEST", error.path("code").asText())
        assertEquals("AI 기록 조회 요청이 올바르지 않습니다.", error.path("message").asText())
        assertEquals("/api/v1/ai/history", error.path("path").asText())
        assertEquals(mapper.createArrayNode(), error.path("fieldErrors"))
        assertEquals(traceId, error.path("traceId").asText())
        UUID.fromString(traceId)
        Instant.parse(error.path("timestamp").asText())
    }

    @Test
    fun `SSE 연결이 끊겨도 저장을 완료하고 재전송은 기록을 중복 생성하지 않는다`() {
        val token = issueToken("history-disconnect-${UUID.randomUUID()}@example.com")
        val requestId = UUID.randomUUID()
        val writeFailures = AtomicInteger()
        val disconnected = MockMvcBuilders.webAppContextSetup(context).addFilters<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(securityFilters, Filter { request, response, chain ->
            chain.doFilter(request, object : HttpServletResponseWrapper(response as HttpServletResponse) {
                override fun getOutputStream(): ServletOutputStream = object : ServletOutputStream() {
                    override fun isReady() = true
                    override fun setWriteListener(listener: WriteListener?) = Unit
                    override fun write(value: Int) {
                        writeFailures.incrementAndGet()
                        throw IOException("Synthetic disconnected SSE client")
                    }
                }
            })
        }).build()
        // 전송 실패는 MVC 초기화 단계에서 전달될 수도 있지만 AI 완료 정책을 바꾸지 않습니다.
        runCatching {
            disconnected.post("/api/v1/ai/chat/stream") {
                header("Authorization", "Bearer $token")
                contentType = MediaType.APPLICATION_JSON
                accept = MediaType.TEXT_EVENT_STREAM
                content = """{"question":"창세기 말씀을 설명해주세요","clientRequestId":"$requestId"}"""
            }.andReturn()
        }
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
        while (!streamingRunner.isIdle() && System.nanoTime() < deadline) Thread.sleep(10)
        org.junit.jupiter.api.Assertions.assertTrue(streamingRunner.isIdle())
        org.junit.jupiter.api.Assertions.assertTrue(writeFailures.get() > 0)
        val completed = mockMvc.get("/api/v1/ai/requests/$requestId") {
            header("Authorization", "Bearer $token")
        }.andExpect { status { isOk() }; jsonPath("$.status") { value("COMPLETED") } }.andReturn()
        val turnId = mapper.readTree(completed.response.contentAsString).path("turnId").asText()
        mockMvc.get("/api/v1/ai/history/$turnId") {
            header("Authorization", "Bearer $token")
        }.andExpect { status { isOk() }; jsonPath("$.versions.length()") { value(1) } }
        val replay = mockMvc.post("/api/v1/ai/chat/stream") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.TEXT_EVENT_STREAM
            content = """{"question":"창세기 말씀을 설명해주세요","clientRequestId":"$requestId"}"""
        }.andReturn()
        AiSseTestSupport.awaitCompletion(replay, streamingRunner::isIdle, "done", setOf("delta", "response", "citations", "usage", "done"))
        mockMvc.get("/api/v1/ai/history") {
            header("Authorization", "Bearer $token")
        }.andExpect { status { isOk() }; jsonPath("$.items.length()") { value(1) }; jsonPath("$.items[0].turnId") { value(turnId) } }
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
        return mapper.readTree(login.response.contentAsString).path("token").asText()
    }
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun postgres(registry: DynamicPropertyRegistry) = AiHistoryTestConfiguration.postgres(registry)
    }

}
