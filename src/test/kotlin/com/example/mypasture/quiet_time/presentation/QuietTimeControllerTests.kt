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
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/** 큐티 QR의 생성, 기간 제한, 재발급, 폐기, 중복 참여 정책을 검증하는 통합 테스트입니다. */
@SpringBootTest
@AutoConfigureMockMvc
class QuietTimeControllerTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {
    private var cachedLeaderToken: String? = null

    @Test
    fun `recommended passages return selection criteria`() {
        mockMvc.get("/api/v1/quiet-time/passages/recommendations") {
            param("theme", "불안")
            param("beginnerOnly", "true")
            param("size", "3")
        }.andExpect {
            status { isOk() }
            jsonPath("$[0].title") { value("불안한 마음을 하나님께 맡기기") }
            jsonPath("$[0].criteria.readabilityScore") { value(5) }
            jsonPath("$[0].criteria.totalScore") { value(25) }
            jsonPath("$[0].observationQuestions.length()") { value(2) }
            jsonPath("$[0].sharingQuestions.length()") { value(2) }
        }
    }

    @Test
    fun `one passage exposes multiple weighted themes and creates different previews`() {
        val passageResult = mockMvc.get("/api/v1/quiet-time/passages/recommendations") {
            param("theme", "불안")
            param("size", "1")
        }.andExpect { status { isOk() } }.andReturn()
        val passageId = objectMapper.readTree(passageResult.response.contentAsString)[0]["id"].asText()

        mockMvc.get("/api/v1/quiet-time/passages/$passageId/themes")
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(3) }
                jsonPath("$[0].code") { value("PEACE") }
                jsonPath("$[0].primary") { value(true) }
                jsonPath("$[1].primary") { value(false) }
                jsonPath("$[0].evidenceReference") { value("빌립보서 4:7") }
            }

        val peace = previewQuestionSet(passageId, "PEACE", "REFLECTION")
        val prayer = previewQuestionSet(passageId, "PRAYER", "REFLECTION")
        check(peace["meditationQuestions"][0].asText() != prayer["meditationQuestions"][0].asText())
        check(peace["focusTheme"]["name"].asText() == "평안")
        check(prayer["focusTheme"]["name"].asText() == "기도")
    }

    @Test
    fun `session freezes selected theme question set and answers use the snapshot`() {
        val passageResult = mockMvc.get("/api/v1/quiet-time/passages/recommendations") {
            param("theme", "불안")
            param("size", "1")
        }.andReturn()
        val passageId = objectMapper.readTree(passageResult.response.contentAsString)[0]["id"].asText()
        val pastureId = createPasture()
        val result = mockMvc.post("/api/v1/quiet-time/pastures/$pastureId/sessions") {
            header("Authorization", "Bearer ${leaderToken()}")
            contentType = MediaType.APPLICATION_JSON
            content =
                """
                {
                  "title": "평안을 나누는 QT",
                  "passageId": "$passageId",
                  "focusThemeCode": "PEACE",
                  "questionStyle": "SHARING"
                }
                """.trimIndent()
        }.andExpect {
            status { isOk() }
            jsonPath("$.questionSet.focusTheme.code") { value("PEACE") }
            jsonPath("$.questionSet.style") { value("SHARING") }
            jsonPath("$.questionSet.lockedAt") { exists() }
            jsonPath("$.questionSet.sharingQuestions.length()") { value(2) }
        }.andReturn()
        val session = objectMapper.readTree(result.response.contentAsString)
        val expectedQuestion = session["questionSet"]["sharingQuestions"][0].asText()

        mockMvc.put("/api/v1/quiet-time/sessions/${session["id"].asText()}/answers/SHARING/0") {
            header("Authorization", "Bearer ${leaderToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"content":"함께 기도하며 나누겠습니다."}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.questionText") { value(expectedQuestion) }
        }
    }

    @Test
    fun `question preview rejects a theme not linked to the passage`() {
        val passageResult = mockMvc.get("/api/v1/quiet-time/passages/recommendations") {
            param("theme", "불안")
            param("size", "1")
        }.andReturn()
        val passageId = objectMapper.readTree(passageResult.response.contentAsString)[0]["id"].asText()

        mockMvc.post("/api/v1/quiet-time/passages/$passageId/question-preview") {
            header("Authorization", "Bearer ${leaderToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"focusThemeCode":"FORGIVENESS","questionStyle":"BALANCED"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_QUIET_TIME_REQUEST") }
        }
    }

    @Test
    fun `pasture session creates expiring invite url for frontend qr`() {
        val session = createSession(createPasture())
        val offlineCode = session["offlineCode"].asText()
        val joinCode = session["joinCode"].asText()
        val meetingEndsAt = Instant.parse(session["meetingEndsAt"].asText())
        val expiresAt = Instant.parse(session["expiresAt"].asText())

        check(ChronoUnit.MINUTES.between(meetingEndsAt, expiresAt) == 30L)
        check(session["scope"].asText() == "PASTURE")
        check(!session["pasture"].isNull)
        check(joinCode.matches(Regex("^[A-HJ-NP-Z2-9]{6}$")))

        mockMvc.get("/api/v1/quiet-time/offline/$offlineCode")
            .andExpect {
                status { isOk() }
                jsonPath("$.offlineCode") { value(offlineCode) }
                jsonPath("$.inviteUrl") { value("https://mypasture.app/q/qt/$offlineCode") }
                jsonPath("$.qrStatus") { value("OPEN") }
                jsonPath("$.joinStartsAt") { exists() }
                jsonPath("$.expiresAt") { exists() }
            }

    }

    @Test
    fun `authenticated member resolves a normalized six character join code`() {
        val session = createSession(createPasture())
        val joinCode = session["joinCode"].asText()

        mockMvc.post("/api/v1/quiet-time/join-codes/resolve") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"joinCode":"$joinCode"}"""
        }.andExpect {
            status { isUnauthorized() }
        }

        mockMvc.post("/api/v1/quiet-time/join-codes/resolve") {
            header("Authorization", "Bearer ${leaderToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"joinCode":"  ${joinCode.lowercase()}  "}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.joinCode") { value(joinCode) }
            jsonPath("$.offlineCode") { value(session["offlineCode"].asText()) }
            jsonPath("$.qrStatus") { value("OPEN") }
        }

        mockMvc.post("/api/v1/quiet-time/join-codes/resolve") {
            header("Authorization", "Bearer ${leaderToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"joinCode":"IO01"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_QUIET_TIME_REQUEST") }
        }
    }

    @Test
    fun `managed pastures only return the authenticated leaders pastures`() {
        val mine = createPasture()
        createPasture(token = createUserToken(), name = "다른 리더 목장")

        mockMvc.get("/api/v1/quiet-time/pastures/mine") {
            header("Authorization", "Bearer ${leaderToken()}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].id") { value(mine.toString()) }
        }
    }

    @Test
    fun `standalone session is created without a pasture and records nullable pasture attendance`() {
        val session = createStandaloneSession()
        val sessionId = session["id"].asText()
        val offlineCode = session["offlineCode"].asText()

        check(session["scope"].asText() == "STANDALONE")
        check(session["pasture"].isNull)
        check(session["hostDisplayName"].asText() == "큐티 리더")

        joinSession(offlineCode).andExpect {
            status { isOk() }
            jsonPath("$.session.scope") { value("STANDALONE") }
            jsonPath("$.participantCount") { value(1) }
        }

        val pastureId = jdbcTemplate.queryForObject(
            "select pasture_id from pasture_attendances where session_id = ?",
            UUID::class.java,
            UUID.fromString(sessionId),
        )
        check(pastureId == null)
    }

    @Test
    fun `only standalone host can manage its qr`() {
        val session = createStandaloneSession()

        mockMvc.delete("/api/v1/quiet-time/sessions/${session["id"].asText()}/qr") {
            header("Authorization", "Bearer ${createUserToken()}")
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("QUIET_TIME_LEADER_REQUIRED") }
        }

        mockMvc.delete("/api/v1/quiet-time/sessions/${session["id"].asText()}/qr") {
            header("Authorization", "Bearer ${leaderToken()}")
        }.andExpect {
            status { isNoContent() }
        }
    }

    @Test
    fun `standalone host can answer without joining the qr session`() {
        val session = createStandaloneSession()

        mockMvc.put("/api/v1/quiet-time/sessions/${session["id"].asText()}/answers/MEDITATION/0") {
            header("Authorization", "Bearer ${leaderToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"content":"일회성 모임 생성자가 작성한 묵상입니다."}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.authorDisplayName") { value("큐티 리더") }
            jsonPath("$.content") { value("일회성 모임 생성자가 작성한 묵상입니다.") }
        }
    }

    @Test
    fun `scanned qr code joins offline session idempotently`() {
        val offlineCode = createSession(createPasture())["offlineCode"].asText()

        joinSession(offlineCode)
            .andExpect {
                status { isOk() }
                jsonPath("$.participant.displayName") { value("도한") }
                jsonPath("$.participantCount") { value(1) }
                jsonPath("$.joinedAlready") { value(false) }
            }

        joinSession(offlineCode)
            .andExpect {
                status { isOk() }
                jsonPath("$.participantCount") { value(1) }
                jsonPath("$.joinedAlready") { value(true) }
            }
    }

    @Test
    fun `scheduled qr rejects participation before join start`() {
        val session = createSession(
            pastureId = createPasture(),
            joinStartsAt = Instant.now().plus(1, ChronoUnit.HOURS),
        )
        val offlineCode = session["offlineCode"].asText()

        joinSession(offlineCode)
            .andExpect {
                status { isConflict() }
                jsonPath("$.code") { value("QUIET_TIME_QR_NOT_OPEN") }
            }
    }

    @Test
    fun `expired qr remains queryable but rejects participation`() {
        val session = createSession(createPasture())
        val offlineCode = session["offlineCode"].asText()
        jdbcTemplate.update(
            "update quiet_time_qr_invitations set expires_at = ? where offline_code = ?",
            Timestamp.from(Instant.now().minusSeconds(1)),
            offlineCode,
        )

        mockMvc.get("/api/v1/quiet-time/offline/$offlineCode")
            .andExpect {
                status { isOk() }
                jsonPath("$.qrStatus") { value("EXPIRED") }
            }

        joinSession(offlineCode)
            .andExpect {
                status { isGone() }
                jsonPath("$.code") { value("QUIET_TIME_QR_EXPIRED") }
            }
    }

    @Test
    fun `reissuing qr revokes previous code and keeps new code open`() {
        val original = createSession(createPasture())
        val sessionId = original["id"].asText()
        val oldCode = original["offlineCode"].asText()
        val oldJoinCode = original["joinCode"].asText()

        val result = mockMvc.post("/api/v1/quiet-time/sessions/$sessionId/qr/reissue") {
            header("Authorization", "Bearer ${leaderToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect {
            status { isOk() }
            jsonPath("$.qrStatus") { value("OPEN") }
        }.andReturn()
        val newCode = objectMapper.readTree(result.response.contentAsString)["offlineCode"].asText()
        val newJoinCode = objectMapper.readTree(result.response.contentAsString)["joinCode"].asText()
        check(oldCode != newCode)
        check(oldJoinCode != newJoinCode)

        mockMvc.post("/api/v1/quiet-time/join-codes/resolve") {
            header("Authorization", "Bearer ${leaderToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"joinCode":"$oldJoinCode"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.qrStatus") { value("REVOKED") }
        }

        mockMvc.get("/api/v1/quiet-time/offline/$oldCode")
            .andExpect {
                status { isOk() }
                jsonPath("$.qrStatus") { value("REVOKED") }
            }

        joinSession(oldCode)
            .andExpect {
                status { isGone() }
                jsonPath("$.code") { value("QUIET_TIME_QR_REVOKED") }
            }

        joinSession(newCode)
            .andExpect {
                status { isOk() }
            }
    }

    @Test
    fun `leader can revoke qr immediately`() {
        val session = createSession(createPasture())
        val sessionId = session["id"].asText()
        val offlineCode = session["offlineCode"].asText()

        mockMvc.delete("/api/v1/quiet-time/sessions/$sessionId/qr") {
            header("Authorization", "Bearer ${leaderToken()}")
        }.andExpect {
            status { isNoContent() }
        }

        joinSession(offlineCode)
            .andExpect {
                status { isGone() }
                jsonPath("$.code") { value("QUIET_TIME_QR_REVOKED") }
            }
    }

    @Test
    fun `closing session blocks participation`() {
        val session = createSession(createPasture())
        val sessionId = session["id"].asText()
        val offlineCode = session["offlineCode"].asText()

        mockMvc.post("/api/v1/quiet-time/sessions/$sessionId/close") {
            header("Authorization", "Bearer ${leaderToken()}")
        }.andExpect {
            status { isNoContent() }
        }

        joinSession(offlineCode)
            .andExpect {
                status { isGone() }
                jsonPath("$.code") { value("QUIET_TIME_SESSION_CLOSED") }
            }
    }

    @Test
    fun `another user cannot manage leader qr`() {
        val session = createSession(createPasture())
        val otherToken = createUserToken()

        mockMvc.delete("/api/v1/quiet-time/sessions/${session["id"].asText()}/qr") {
            header("Authorization", "Bearer $otherToken")
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("QUIET_TIME_LEADER_REQUIRED") }
        }
    }

    @Test
    fun `leader endpoints require authentication`() {
        mockMvc.post("/api/v1/quiet-time/pastures") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"인증 필요 목장","leaderName":"리더"}"""
        }.andExpect {
            status { isUnauthorized() }
        }
    }

    @Test
    fun `joining a qt meeting requires authentication`() {
        val offlineCode = createSession(createPasture())["offlineCode"].asText()

        mockMvc.post("/api/v1/quiet-time/offline/$offlineCode/join") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"displayName":"도한","deviceId":"test-device-unauthorized"}"""
        }.andExpect {
            status { isUnauthorized() }
        }
    }

    @Test
    fun `invalid recommendation size is rejected`() {
        mockMvc.get("/api/v1/quiet-time/passages/recommendations") {
            param("size", "100")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_QUIET_TIME_REQUEST") }
        }
    }

    private fun joinSession(offlineCode: String) =
        mockMvc.post("/api/v1/quiet-time/offline/$offlineCode/join") {
            header("Authorization", "Bearer ${leaderToken()}")
            contentType = MediaType.APPLICATION_JSON
            content =
                """
                {
                  "displayName": "도한",
                  "deviceId": "test-device-001"
                }
                """.trimIndent()
        }

    private fun createPasture(
        token: String = leaderToken(),
        name: String = "오프라인 큐티 목장",
    ): UUID {
        val result = mockMvc.post("/api/v1/quiet-time/pastures") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content =
                """
                {
                  "name": "$name",
                  "leaderName": "도한"
                }
                """.trimIndent()
        }.andExpect {
            status { isOk() }
            jsonPath("$.inviteCode") { exists() }
        }.andReturn()
        return UUID.fromString(objectMapper.readTree(result.response.contentAsString)["id"].asText())
    }

    private fun createSession(
        pastureId: UUID,
        joinStartsAt: Instant = Instant.now().minusSeconds(60),
    ): JsonNode {
        val meetingStartsAt = Instant.now().plusSeconds(60)
        val meetingEndsAt = meetingStartsAt.plus(1, ChronoUnit.HOURS)
        val result = mockMvc.post("/api/v1/quiet-time/pastures/$pastureId/sessions") {
            header("Authorization", "Bearer ${leaderToken()}")
            contentType = MediaType.APPLICATION_JSON
            content =
                """
                {
                  "title": "토요 오프라인 큐티",
                  "theme": "불안",
                  "meetingDate": "${LocalDate.now()}",
                  "meetingStartsAt": "$meetingStartsAt",
                  "meetingEndsAt": "$meetingEndsAt",
                  "joinStartsAt": "$joinStartsAt"
                }
                """.trimIndent()
        }.andExpect {
            status { isOk() }
            jsonPath("$.offlineCode") { exists() }
            jsonPath("$.joinCode") { exists() }
            jsonPath("$.expiresAt") { exists() }
        }.andReturn()
        return objectMapper.readTree(result.response.contentAsString)
    }

    private fun previewQuestionSet(
        passageId: String,
        focusThemeCode: String,
        questionStyle: String,
    ): JsonNode {
        val result = mockMvc.post("/api/v1/quiet-time/passages/$passageId/question-preview") {
            header("Authorization", "Bearer ${leaderToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"focusThemeCode":"$focusThemeCode","questionStyle":"$questionStyle"}"""
        }.andExpect { status { isOk() } }.andReturn()
        return objectMapper.readTree(result.response.contentAsString)
    }

    private fun createStandaloneSession(): JsonNode {
        val meetingStartsAt = Instant.now().plusSeconds(60)
        val meetingEndsAt = meetingStartsAt.plus(1, ChronoUnit.HOURS)
        val result = mockMvc.post("/api/v1/quiet-time/sessions/standalone") {
            header("Authorization", "Bearer ${leaderToken()}")
            contentType = MediaType.APPLICATION_JSON
            content =
                """
                {
                  "title": "오늘만 함께하는 QT",
                  "theme": "불안",
                  "meetingStartsAt": "$meetingStartsAt",
                  "meetingEndsAt": "$meetingEndsAt"
                }
                """.trimIndent()
        }.andExpect {
            status { isOk() }
            jsonPath("$.offlineCode") { exists() }
            jsonPath("$.scope") { value("STANDALONE") }
        }.andReturn()
        return objectMapper.readTree(result.response.contentAsString)
    }

    private fun leaderToken(): String =
        cachedLeaderToken ?: createUserToken().also { cachedLeaderToken = it }

    private fun createUserToken(): String {
        val email = "quiet-time-${UUID.randomUUID()}@example.com"
        val password = "password123!"
        mockMvc.post("/api/v1/auth/signup") {
            contentType = MediaType.APPLICATION_JSON
            content =
                """
                {
                  "email": "$email",
                  "password": "$password",
                  "displayName": "큐티 리더"
                }
                """.trimIndent()
        }.andExpect {
            status { isCreated() }
        }

        val result = mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"$password"}"""
        }.andExpect {
            status { isOk() }
        }.andReturn()
        return objectMapper.readTree(result.response.contentAsString)["token"].asText()
    }
}
