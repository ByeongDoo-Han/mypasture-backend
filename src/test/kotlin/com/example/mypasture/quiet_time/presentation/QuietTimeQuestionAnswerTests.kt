package com.example.mypasture.quiet_time.presentation

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.nio.charset.StandardCharsets
import java.util.UUID

/** QT 질문별 답변의 참여 권한, 최신 상태 저장, SSE 전파를 검증하는 통합 테스트입니다. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = ["app.firebase.enabled=false"])
class QuietTimeQuestionAnswerTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val streamRegistry: QuietTimeAnswerStreamRegistry,
) {
    @Test
    fun `participant publishes and updates one answer visible to the leader`() {
        val fixture = createJoinedSession()

        saveAnswer(fixture.sessionId, fixture.memberToken, "MEDITATION", 0, "처음에는 염려를 내려놓기로 했습니다.")
            .andExpect {
                status { isOk() }
                jsonPath("$.authorDisplayName") { value("목원 한나") }
                jsonPath("$.questionCategory") { value("MEDITATION") }
                jsonPath("$.questionIndex") { value(0) }
                jsonPath("$.questionText") { isNotEmpty() }
            }

        saveAnswer(fixture.sessionId, fixture.memberToken, "MEDITATION", 0, "기도로 염려를 맡기겠습니다.")
            .andExpect {
                status { isOk() }
                jsonPath("$.content") { value("기도로 염려를 맡기겠습니다.") }
            }

        mockMvc.get("/api/v1/quiet-time/sessions/${fixture.sessionId}/answers") {
            header("Authorization", "Bearer ${fixture.leaderToken}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].content") { value("기도로 염려를 맡기겠습니다.") }
            jsonPath("$[0].userId") { value(fixture.memberUserId.toString()) }
        }
    }

    @Test
    fun `outsider cannot read or publish meeting answers`() {
        val fixture = createJoinedSession()
        val outsider = createUser("외부인")

        mockMvc.get("/api/v1/quiet-time/sessions/${fixture.sessionId}/answers") {
            header("Authorization", "Bearer ${outsider.token}")
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("QUIET_TIME_PARTICIPATION_REQUIRED") }
        }

        saveAnswer(fixture.sessionId, outsider.token, "OBSERVATION", 0, "볼 수 없는 답변")
            .andExpect {
                status { isForbidden() }
                jsonPath("$.code") { value("QUIET_TIME_PARTICIPATION_REQUIRED") }
            }
    }

    @Test
    fun `invalid question index is rejected`() {
        val fixture = createJoinedSession()

        saveAnswer(fixture.sessionId, fixture.memberToken, "SHARING", 99, "없는 질문의 답변")
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("INVALID_QUIET_TIME_REQUEST") }
            }
    }

    @Test
    fun `answer update is delivered to an authenticated meeting stream`() {
        val fixture = createJoinedSession()
        val stream = mockMvc.get("/api/v1/quiet-time/sessions/${fixture.sessionId}/answers/stream") {
            header("Authorization", "Bearer ${fixture.leaderToken}")
            accept = MediaType.TEXT_EVENT_STREAM
        }.andExpect {
            request { asyncStarted() }
        }.andReturn()

        saveAnswer(fixture.sessionId, fixture.memberToken, "OBSERVATION", 0, "본문에서 반복되는 단어를 찾았습니다.")
            .andExpect { status { isOk() } }

        waitUntilStreamContains(stream.response, "answer-updated")
        waitUntilStreamContains(stream.response, "본문에서 반복되는 단어를 찾았습니다.")
        streamRegistry.completeSession(fixture.sessionId)

        mockMvc.perform(asyncDispatch(stream))
            .andExpect(status().isOk)
            .andExpect(content().string(org.hamcrest.Matchers.containsString("event:answer-updated")))
    }

    @Test
    fun `closed meeting rejects a new answer stream`() {
        val fixture = createJoinedSession()
        mockMvc.post("/api/v1/quiet-time/sessions/${fixture.sessionId}/close") {
            header("Authorization", "Bearer ${fixture.leaderToken}")
        }.andExpect { status { isNoContent() } }

        mockMvc.get("/api/v1/quiet-time/sessions/${fixture.sessionId}/answers/stream") {
            header("Authorization", "Bearer ${fixture.memberToken}")
            header("Accept", "text/event-stream, application/json")
        }.andExpect {
            status { isGone() }
            jsonPath("$.code") { value("QUIET_TIME_SESSION_CLOSED") }
        }
    }

    private fun saveAnswer(
        sessionId: UUID,
        token: String,
        category: String,
        questionIndex: Int,
        content: String,
    ) = mockMvc.put("/api/v1/quiet-time/sessions/$sessionId/answers/$category/$questionIndex") {
        header("Authorization", "Bearer $token")
        contentType = MediaType.APPLICATION_JSON
        this.content = objectMapper.writeValueAsString(mapOf("content" to content))
    }

    private fun createJoinedSession(): Fixture {
        val leader = createUser("큐티 리더")
        val member = createUser("목원 한나")
        val pastureResult = mockMvc.post("/api/v1/quiet-time/pastures") {
            header("Authorization", "Bearer ${leader.token}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"실시간 큐티 목장","leaderName":"큐티 리더"}"""
        }.andExpect { status { isOk() } }.andReturn()
        val pastureId = objectMapper.readTree(pastureResult.response.contentAsString)["id"].asText()
        val startsAt = Instant.now().plusSeconds(30)
        val sessionResult = mockMvc.post("/api/v1/quiet-time/pastures/$pastureId/sessions") {
            header("Authorization", "Bearer ${leader.token}")
            contentType = MediaType.APPLICATION_JSON
            content =
                """
                {
                  "title": "함께 나누는 큐티",
                  "theme": "불안",
                  "meetingDate": "${LocalDate.now()}",
                  "meetingStartsAt": "$startsAt",
                  "meetingEndsAt": "${startsAt.plus(1, ChronoUnit.HOURS)}",
                  "joinStartsAt": "${Instant.now().minusSeconds(60)}"
                }
                """.trimIndent()
        }.andExpect { status { isOk() } }.andReturn()
        val session = objectMapper.readTree(sessionResult.response.contentAsString)

        mockMvc.post("/api/v1/quiet-time/offline/${session["offlineCode"].asText()}/join") {
            header("Authorization", "Bearer ${member.token}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"displayName":"목원 한나","deviceId":"${UUID.randomUUID()}"}"""
        }.andExpect { status { isOk() } }

        return Fixture(
            sessionId = UUID.fromString(session["id"].asText()),
            leaderToken = leader.token,
            memberToken = member.token,
            memberUserId = member.userId,
        )
    }

    private fun createUser(displayName: String): UserSession {
        val email = "qt-answer-${UUID.randomUUID()}@example.com"
        val password = "password123!"
        mockMvc.post("/api/v1/auth/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                mapOf("email" to email, "password" to password, "displayName" to displayName),
            )
        }.andExpect { status { isCreated() } }

        val loginResult = mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("email" to email, "password" to password))
        }.andExpect { status { isOk() } }.andReturn()
        val response: JsonNode = objectMapper.readTree(loginResult.response.contentAsString)
        val token = response["token"].asText()
        val userId = objectMapper.readTree(
            String(java.util.Base64.getUrlDecoder().decode(token.split(".")[1])),
        )["sub"].asText()
        return UserSession(UUID.fromString(userId), token)
    }

    private fun waitUntilStreamContains(response: jakarta.servlet.http.HttpServletResponse, expected: String) {
        val mockResponse = response as org.springframework.mock.web.MockHttpServletResponse
        repeat(50) {
            if (mockResponse.getContentAsString(StandardCharsets.UTF_8).contains(expected)) return
            Thread.sleep(20)
        }
        check(mockResponse.getContentAsString(StandardCharsets.UTF_8).contains(expected)) {
            "SSE response did not contain '$expected': ${mockResponse.getContentAsString(StandardCharsets.UTF_8)}"
        }
    }

    private data class UserSession(val userId: UUID, val token: String)

    private data class Fixture(
        val sessionId: UUID,
        val leaderToken: String,
        val memberToken: String,
        val memberUserId: UUID,
    )
}
