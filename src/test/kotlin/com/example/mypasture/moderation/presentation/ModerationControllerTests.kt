package com.example.mypasture.moderation.presentation

import com.example.mypasture.auth.domain.uniqueTestNickname

import com.example.mypasture.ai.domain.AiRequestReceiptEntity
import com.example.mypasture.ai.domain.AiUsageLogEntity
import com.example.mypasture.ai.domain.AiUsageStatus
import com.example.mypasture.ai.domain.GroundedAnswer
import com.example.mypasture.ai.infrastructure.AiRequestReceiptRepository
import com.example.mypasture.ai.infrastructure.AiRequestResponseCodec
import com.example.mypasture.ai.infrastructure.AiUsageLogRepository
import com.example.mypasture.auth.infrastructure.UserRepository
import com.example.mypasture.moderation.application.ModerationService
import com.example.mypasture.moderation.application.ModerationTargetUnavailableException
import com.example.mypasture.moderation.domain.ModerationReportReason
import com.example.mypasture.moderation.domain.ModerationTargetType
import com.example.mypasture.user.domain.UserEntity
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/** AI·QT 신고 접수, 사용자 차단, 관리자 숨김 조치를 HTTP 흐름으로 검증합니다. */
@SpringBootTest
@AutoConfigureMockMvc
class ModerationControllerTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val users: UserRepository,
    @Autowired private val moderationService: ModerationService,
    @Autowired private val aiUsageLogs: AiUsageLogRepository,
    @Autowired private val aiReceipts: AiRequestReceiptRepository,
    @Autowired private val aiResponseCodec: AiRequestResponseCodec,
) {
    @Test
    fun `참여자가 QT 답변을 신고 차단하고 관리자가 숨긴다`() {
        val fixture = createJoinedSession()
        val answerId = saveAnswer(fixture.sessionId, fixture.member.token, "신고 검증용 QT 답변")
        val reportBody = mapOf(
            "targetType" to "QUIET_TIME_ANSWER",
            "targetId" to answerId,
            "reason" to "HARASSMENT",
            "details" to "다른 목원에게 불편을 줄 수 있는 표현입니다.",
        )
        val firstReport = mockMvc.post("/api/v1/moderation/reports") {
            header("Authorization", "Bearer ${fixture.leader.token}")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(reportBody)
        }.andExpect {
            status { isCreated() }
            jsonPath("$.status") { value("OPEN") }
            jsonPath("$.targetId") { value(answerId) }
        }.andReturn()
        val reportId = objectMapper.readTree(firstReport.response.contentAsString)["id"].asText()

        mockMvc.post("/api/v1/moderation/reports") {
            header("Authorization", "Bearer ${fixture.leader.token}")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(reportBody + ("reason" to "OFFENSIVE"))
        }.andExpect {
            status { isCreated() }
            jsonPath("$.id") { value(reportId) }
        }

        mockMvc.put("/api/v1/moderation/blocks/${fixture.member.userId}") {
            header("Authorization", "Bearer ${fixture.leader.token}")
        }.andExpect { status { isNoContent() } }
        assertAnswerCount(fixture.sessionId, fixture.leader.token, 0)

        mockMvc.delete("/api/v1/moderation/blocks/${fixture.member.userId}") {
            header("Authorization", "Bearer ${fixture.leader.token}")
        }.andExpect { status { isNoContent() } }
        assertAnswerCount(fixture.sessionId, fixture.leader.token, 1)

        mockMvc.patch("/api/v1/admin/moderation/reports/$reportId") {
            header("Authorization", "Bearer ${adminToken()}")
            contentType = MediaType.APPLICATION_JSON
            content =
                """{"operationId":"${UUID.randomUUID()}","status":"RESOLVED","action":"CONTENT_HIDDEN","note":"운영 정책 검토 후 해당 답변을 숨김 처리합니다."}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.action") { value("CONTENT_HIDDEN") }
            jsonPath("$.targetSnapshot") { value(org.hamcrest.Matchers.containsString("신고 검증용 QT 답변")) }
        }
        assertAnswerCount(fixture.sessionId, fixture.leader.token, 0)
        assertAnswerCount(fixture.sessionId, fixture.member.token, 0)

        mockMvc.post("/api/v1/moderation/reports") {
            header("Authorization", "Bearer ${fixture.leader.token}")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(reportBody + ("reason" to "PRIVACY"))
        }.andExpect {
            status { isCreated() }
            jsonPath("$.status") { value("OPEN") }
        }
        assertAnswerCount(fixture.sessionId, fixture.leader.token, 0)
        assertAnswerCount(fixture.sessionId, fixture.member.token, 0)
    }

    @Test
    fun `일반 사용자는 신고 관리 대기열을 볼 수 없다`() {
        mockMvc.get("/api/v1/admin/moderation/reports") {
            header("Authorization", "Bearer ${createUser("일반사용자").token}")
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("ACCESS_DENIED") }
        }
    }

    @Test
    fun `악성 확률이 높은 QT 답변은 게시 전에 거부한다`() {
        val fixture = createJoinedSession()
        mockMvc.put("/api/v1/quiet-time/sessions/${fixture.sessionId}/answers/MEDITATION/0") {
            header("Authorization", "Bearer ${fixture.member.token}")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("content" to "너는 그냥 자살해."))
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("QUIET_TIME_CONTENT_REJECTED") }
        }
        assertAnswerCount(fixture.sessionId, fixture.leader.token, 0)
    }

    @Test
    fun `완료된 AI 답변은 소유자만 신고할 수 있다`() {
        val owner = users.save(UserEntity(email = "moderation-ai-owner-${UUID.randomUUID()}@example.com", displayName = "AI Owner"))
        val stranger = users.save(UserEntity(email = "moderation-ai-stranger-${UUID.randomUUID()}@example.com", displayName = "AI Stranger"))
        val turnId = saveCompletedAiAnswer(owner.id)

        moderationService.submitReport(
            owner.id,
            ModerationTargetType.AI_ANSWER,
            turnId,
            ModerationReportReason.FALSE_INFORMATION,
            "인용 내용을 다시 확인해 주세요.",
        )

        assertThrows(ModerationTargetUnavailableException::class.java) {
            moderationService.submitReport(
                stranger.id,
                ModerationTargetType.AI_ANSWER,
                turnId,
                ModerationReportReason.FALSE_INFORMATION,
                null,
            )
        }
    }

    private fun saveCompletedAiAnswer(userId: UUID): UUID {
        val turnId = UUID.randomUUID()
        val now = Instant.now()
        val answer = GroundedAnswer(
            conversationId = null,
            turnId = turnId,
            answer = "태초에 하나님이 천지를 창조하셨다는 내용을 설명합니다.",
            citations = emptyList(),
            promptTokens = 1,
            completionTokens = 1,
            estimatedCostUsd = BigDecimal.ZERO,
            cacheHit = false,
        )
        aiUsageLogs.save(
            AiUsageLogEntity(
                id = turnId,
                userId = userId,
                requestHash = "moderation-test-$turnId",
                model = "test-model",
                status = AiUsageStatus.COMPLETED,
            ),
        )
        val receipt = AiRequestReceiptEntity(
            userId = userId,
            clientRequestId = UUID.randomUUID(),
            usageLogId = turnId,
            requestHash = "moderation-test-$turnId",
        )
        receipt.complete(aiResponseCodec.encode(answer), now.plus(1, ChronoUnit.DAYS), now)
        aiReceipts.save(receipt)
        return turnId
    }

    private fun assertAnswerCount(sessionId: UUID, token: String, count: Int) {
        mockMvc.get("/api/v1/quiet-time/sessions/$sessionId/answers") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(count) }
        }
    }

    private fun saveAnswer(sessionId: UUID, token: String, content: String): String {
        val result = mockMvc.put("/api/v1/quiet-time/sessions/$sessionId/answers/MEDITATION/0") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            this.content = objectMapper.writeValueAsString(mapOf("content" to content))
        }.andExpect { status { isOk() } }.andReturn()
        return objectMapper.readTree(result.response.contentAsString)["id"].asText()
    }

    private fun createJoinedSession(): Fixture {
        val leader = createUser("큐티리더")
        val member = createUser("목원한나")
        val pasture = mockMvc.post("/api/v1/quiet-time/pastures") {
            header("Authorization", "Bearer ${leader.token}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"신고 테스트 목장","leaderName":"큐티 리더"}"""
        }.andExpect { status { isOk() } }.andReturn()
        val pastureId = objectMapper.readTree(pasture.response.contentAsString)["id"].asText()
        val startsAt = Instant.now().plusSeconds(30)
        val session = mockMvc.post("/api/v1/quiet-time/pastures/$pastureId/sessions") {
            header("Authorization", "Bearer ${leader.token}")
            contentType = MediaType.APPLICATION_JSON
            content =
                """
                {
                  "title":"신고 테스트 QT",
                  "theme":"불안",
                  "meetingDate":"${LocalDate.now()}",
                  "meetingStartsAt":"$startsAt",
                  "meetingEndsAt":"${startsAt.plus(1, ChronoUnit.HOURS)}",
                  "joinStartsAt":"${Instant.now().minusSeconds(60)}"
                }
                """.trimIndent()
        }.andExpect { status { isOk() } }.andReturn()
        val sessionJson = objectMapper.readTree(session.response.contentAsString)
        mockMvc.post("/api/v1/quiet-time/offline/${sessionJson["offlineCode"].asText()}/join") {
            header("Authorization", "Bearer ${member.token}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"displayName":"목원 한나","deviceId":"${UUID.randomUUID()}"}"""
        }.andExpect { status { isOk() } }
        return Fixture(UUID.fromString(sessionJson["id"].asText()), leader, member)
    }

    private fun createUser(displayName: String): UserSession {
        val email = "moderation-${UUID.randomUUID()}@example.com"
        mockMvc.post("/api/v1/auth/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("email" to email, "password" to PASSWORD, "displayName" to uniqueTestNickname(displayName)))
        }.andExpect { status { isCreated() } }
        val token = login(email)
        val userId = objectMapper.readTree(
            String(java.util.Base64.getUrlDecoder().decode(token.split('.')[1])),
        )["sub"].asText()
        return UserSession(UUID.fromString(userId), token)
    }

    private fun adminToken(): String {
        val email = "moderation-admin-${UUID.randomUUID()}@example.com"
        mockMvc.post("/api/v1/auth/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("email" to email, "password" to PASSWORD, "displayName" to uniqueTestNickname()))
        }.andExpect { status { isCreated() } }
        jdbcTemplate.update("update users set role = 'ADMIN' where email = ?", email)
        return login(email)
    }

    private fun login(email: String): String {
        val result = mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("email" to email, "password" to PASSWORD))
        }.andExpect { status { isOk() } }.andReturn()
        return objectMapper.readTree(result.response.contentAsString)["token"].asText()
    }

    private data class UserSession(val userId: UUID, val token: String)
    private data class Fixture(val sessionId: UUID, val leader: UserSession, val member: UserSession)

    companion object {
        private const val PASSWORD = "password123!"
    }
}
