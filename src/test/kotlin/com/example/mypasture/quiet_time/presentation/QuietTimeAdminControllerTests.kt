package com.example.mypasture.quiet_time.presentation

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/** QT 관리자 조회, 권한, 멱등 조치와 감사 로그를 검증하는 통합 테스트입니다. */
@SpringBootTest
@AutoConfigureMockMvc
class QuietTimeAdminControllerTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {
    private var cachedAdminToken: String? = null

    @Test
    fun `ordinary user cannot access admin quiet time endpoints`() {
        mockMvc.get("/api/v1/admin/quiet-time/sessions") {
            header("Authorization", "Bearer ${createUserToken("일반 사용자")}")
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("ACCESS_DENIED") }
        }
    }

    @Test
    fun `admin can filter standalone sessions without receiving answer content`() {
        val uniqueTitle = "관리자 필터 ${UUID.randomUUID()}"
        val session = createStandaloneSession(uniqueTitle)
        writeAnswer(session["id"].asText(), "목록에 노출되면 안 되는 민감한 묵상")

        val result = mockMvc.get("/api/v1/admin/quiet-time/sessions") {
            header("Authorization", "Bearer ${adminToken()}")
            param("scope", "STANDALONE")
            param("search", uniqueTitle)
            param("size", "10")
        }.andExpect {
            status { isOk() }
            jsonPath("$.totalElements") { value(1) }
            jsonPath("$.content[0].scope") { value("STANDALONE") }
            jsonPath("$.content[0].pastureName") { doesNotExist() }
            jsonPath("$.content[0].answerCount") { value(1) }
        }.andReturn()

        check(!result.response.contentAsString.contains("민감한 묵상"))
    }

    @Test
    fun `statistics increase when a standalone session is created`() {
        val before = statistics()
        createStandaloneSession("통계 확인 ${UUID.randomUUID()}")
        val after = statistics()

        check(after["totalSessions"].asLong() == before["totalSessions"].asLong() + 1)
        check(after["standaloneSessions"].asLong() == before["standaloneSessions"].asLong() + 1)
    }

    @Test
    fun `force close is idempotent and stores one audit record`() {
        val session = createStandaloneSession("강제 종료 ${UUID.randomUUID()}")
        val sessionId = session["id"].asText()
        val operationId = UUID.randomUUID()
        val requestBody = """{"operationId":"$operationId","reason":"운영 정책 위반 세션 강제 종료"}"""

        repeat(2) {
            mockMvc.post("/api/v1/admin/quiet-time/sessions/$sessionId/close") {
                header("Authorization", "Bearer ${adminToken()}")
                contentType = MediaType.APPLICATION_JSON
                content = requestBody
            }.andExpect {
                status { isOk() }
                jsonPath("$.summary.status") { value("CLOSED") }
                jsonPath("$.recentActions.length()") { value(1) }
                jsonPath("$.recentActions[0].action") { value("QUIET_TIME_FORCE_CLOSED") }
            }
        }

        val auditCount = jdbcTemplate.queryForObject(
            "select count(*) from admin_audit_logs where operation_id = ?",
            Long::class.java,
            operationId,
        )
        check(auditCount == 1L)
    }

    @Test
    fun `admin qr revocation blocks later participation`() {
        val session = createStandaloneSession("QR 폐기 ${UUID.randomUUID()}")
        val sessionId = session["id"].asText()
        val offlineCode = session["offlineCode"].asText()

        mockMvc.post("/api/v1/admin/quiet-time/sessions/$sessionId/revoke-qr") {
            header("Authorization", "Bearer ${adminToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"operationId":"${UUID.randomUUID()}","reason":"노출된 QR 초대 링크 즉시 폐기"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.qrStatus") { value("REVOKED") }
            jsonPath("$.recentActions[0].action") { value("QUIET_TIME_QR_REVOKED") }
        }

        mockMvc.post("/api/v1/quiet-time/offline/$offlineCode/join") {
            header("Authorization", "Bearer ${createUserToken("참여 시도 사용자")}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"displayName":"참여자","deviceId":"admin-test-device-001"}"""
        }.andExpect {
            status { isGone() }
            jsonPath("$.code") { value("QUIET_TIME_QR_REVOKED") }
        }
    }

    @Test
    fun `admin action validates reason and rejects reused operation id`() {
        val first = createStandaloneSession("멱등 키 첫 대상 ${UUID.randomUUID()}")
        val second = createStandaloneSession("멱등 키 둘째 대상 ${UUID.randomUUID()}")
        val operationId = UUID.randomUUID()

        mockMvc.post("/api/v1/admin/quiet-time/sessions/${first["id"].asText()}/close") {
            header("Authorization", "Bearer ${adminToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"operationId":"$operationId","reason":"관리자 확인 후 정상 종료 처리"}"""
        }.andExpect { status { isOk() } }

        mockMvc.post("/api/v1/admin/quiet-time/sessions/${second["id"].asText()}/close") {
            header("Authorization", "Bearer ${adminToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"operationId":"$operationId","reason":"다른 대상에 같은 키를 잘못 사용"}"""
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("QUIET_TIME_ADMIN_OPERATION_CONFLICT") }
        }

        mockMvc.post("/api/v1/admin/quiet-time/sessions/${second["id"].asText()}/close") {
            header("Authorization", "Bearer ${adminToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"operationId":"${UUID.randomUUID()}","reason":"짧음"}"""
        }.andExpect { status { isBadRequest() } }
    }

    private fun statistics(): JsonNode {
        val today = LocalDate.now()
        val result = mockMvc.get("/api/v1/admin/quiet-time/statistics") {
            header("Authorization", "Bearer ${adminToken()}")
            param("from", today.minusDays(1).toString())
            param("to", today.plusDays(1).toString())
        }.andExpect { status { isOk() } }.andReturn()
        return objectMapper.readTree(result.response.contentAsString)
    }

    private fun createStandaloneSession(title: String): JsonNode {
        val startsAt = Instant.now().plusSeconds(30)
        val endsAt = startsAt.plus(1, ChronoUnit.HOURS)
        val result = mockMvc.post("/api/v1/quiet-time/sessions/standalone") {
            header("Authorization", "Bearer ${createUserToken("QT 호스트")}")
            contentType = MediaType.APPLICATION_JSON
            content =
                """
                {
                  "title": "$title",
                  "theme": "불안",
                  "meetingStartsAt": "$startsAt",
                  "meetingEndsAt": "$endsAt"
                }
                """.trimIndent()
        }.andExpect { status { isOk() } }.andReturn()
        return objectMapper.readTree(result.response.contentAsString)
    }

    private fun writeAnswer(sessionId: String, answer: String) {
        val hostToken = jdbcTemplate.queryForObject(
            "select host_user_id from quiet_time_sessions where id = ?",
            UUID::class.java,
            UUID.fromString(sessionId),
        ) ?: error("Host user id was not stored")
        val email = jdbcTemplate.queryForObject("select email from users where id = ?", String::class.java, hostToken)
            ?: error("Host email was not stored")
        val token = login(email, DEFAULT_PASSWORD)
        mockMvc.put("/api/v1/quiet-time/sessions/$sessionId/answers/MEDITATION/0") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("content" to answer))
        }.andExpect { status { isOk() } }
    }

    private fun adminToken(): String =
        cachedAdminToken ?: run {
            val email = signup("운영 관리자")
            jdbcTemplate.update("update users set role = 'ADMIN' where email = ?", email)
            login(email, DEFAULT_PASSWORD).also { cachedAdminToken = it }
        }

    private fun createUserToken(displayName: String): String {
        val email = signup(displayName)
        return login(email, DEFAULT_PASSWORD)
    }

    private fun signup(displayName: String): String {
        val email = "qt-admin-${UUID.randomUUID()}@example.com"
        mockMvc.post("/api/v1/auth/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                mapOf("email" to email, "password" to DEFAULT_PASSWORD, "displayName" to displayName),
            )
        }.andExpect { status { isCreated() } }
        return email
    }

    private fun login(email: String, password: String): String {
        val result = mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("email" to email, "password" to password))
        }.andExpect { status { isOk() } }.andReturn()
        return objectMapper.readTree(result.response.contentAsString)["token"].asText()
    }

    companion object {
        private const val DEFAULT_PASSWORD = "password123!"
    }
}
