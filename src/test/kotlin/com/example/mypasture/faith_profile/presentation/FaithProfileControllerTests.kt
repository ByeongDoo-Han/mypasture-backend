package com.example.mypasture.faith_profile.presentation

import com.example.mypasture.auth.domain.uniqueTestNickname

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID

/** FaithProfileController의 테스트 조회, 시작, 답변, 완료 HTTP 흐름을 검증하는 통합 테스트입니다. */
@SpringBootTest
@AutoConfigureMockMvc
class FaithProfileControllerTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
) {
    private lateinit var token: String

    @BeforeEach
    fun authenticate() {
        val email = "faith-${UUID.randomUUID()}@example.com"
        val password = "password123!"
        mockMvc.post("/api/v1/auth/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"$password","displayName":"${uniqueTestNickname()}"}"""
        }.andExpect { status { isCreated() } }
        val login = mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"$password"}"""
        }.andReturn()
        token = objectMapper.readTree(login.response.contentAsString)["token"].asText()
    }

    @Test
    fun `현재 신앙 검사에는 기본 믿음 유형 정의가 반환된다`() {
        mockMvc.get("/api/v1/faith-profile/tests/current")
            .andExpect {
                status { isOk() }
                jsonPath("$.code") { value("FAITH_RHYTHM") }
                jsonPath("$.title") { value("믿음 리듬 테스트") }
                jsonPath("$.dimensions.length()") { value(4) }
                jsonPath("$.questions.length()") { value(8) }
            }
    }

    @Test
    fun `검사를 완료하면 신앙 유형 결과를 반환한다`() {
        val test = getCurrentTest()
        val assessmentId = startAssessment()

        val scoreBySortOrder = mapOf(
            1 to 5,
            2 to 1,
            3 to 5,
            4 to 1,
            5 to 5,
            6 to 1,
            7 to 5,
            8 to 1,
        )
        test["questions"].forEach { question ->
            submitAnswer(
                assessmentId = assessmentId,
                questionId = UUID.fromString(question["id"].asText()),
                score = scoreBySortOrder.getValue(question["sortOrder"].asInt()),
            )
        }

        val result = completeAssessment(assessmentId)
        val resultId = UUID.fromString(result["id"].asText())

        mockMvc.get("/api/v1/faith-profile/results/$resultId") {
            header("Authorization", "Bearer $token")
        }.andExpect {
                status { isOk() }
                jsonPath("$.typeCode") { value("WMCG") }
                jsonPath("$.typeName") { value("조용한 말씀 순례자") }
                jsonPath("$.dimensionScores.length()") { value(4) }
                jsonPath("$.recommendations.length()") { value(3) }
            }

        val otherUserToken = issueToken()
        mockMvc.get("/api/v1/faith-profile/results/$resultId") {
            header("Authorization", "Bearer $otherUserToken")
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("FAITH_PROFILE_FORBIDDEN") }
        }
    }

    @Test
    fun `답변이 빠진 검사는 완료를 거부한다`() {
        val assessmentId = startAssessment()

        mockMvc.post("/api/v1/faith-profile/assessments/$assessmentId/complete") {
            header("Authorization", "Bearer $token")
        }.andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("INVALID_FAITH_PROFILE_REQUEST") }
            }
    }

    @Test
    fun `중복 답변을 거부한다`() {
        val questionId = UUID.fromString(getCurrentTest()["questions"].first()["id"].asText())
        val assessmentId = startAssessment()

        submitAnswer(assessmentId, questionId, 3)

        mockMvc.post("/api/v1/faith-profile/assessments/$assessmentId/answers") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = answerPayload(questionId, 4)
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("FAITH_ANSWER_ALREADY_EXISTS") }
        }
    }

    private fun getCurrentTest(): JsonNode {
        val mvcResult = mockMvc.get("/api/v1/faith-profile/tests/current")
            .andExpect { status { isOk() } }
            .andReturn()
        return objectMapper.readTree(mvcResult.response.contentAsString)
    }

    private fun startAssessment(): UUID {
        val mvcResult = mockMvc.post("/api/v1/faith-profile/assessments") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("IN_PROGRESS") }
        }.andReturn()
        return UUID.fromString(objectMapper.readTree(mvcResult.response.contentAsString)["id"].asText())
    }

    private fun submitAnswer(
        assessmentId: UUID,
        questionId: UUID,
        score: Int,
    ) {
        mockMvc.post("/api/v1/faith-profile/assessments/$assessmentId/answers") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = answerPayload(questionId, score)
        }.andExpect {
            status { isOk() }
            jsonPath("$.score") { value(score) }
        }
    }

    private fun completeAssessment(assessmentId: UUID): JsonNode {
        val mvcResult = mockMvc.post("/api/v1/faith-profile/assessments/$assessmentId/complete") {
            header("Authorization", "Bearer $token")
        }.andExpect {
                status { isOk() }
                jsonPath("$.typeCode") { value("WMCG") }
                jsonPath("$.summary") { exists() }
            }.andReturn()
        return objectMapper.readTree(mvcResult.response.contentAsString)
    }

    private fun answerPayload(
        questionId: UUID,
        score: Int,
    ): String =
        """
        {
          "questionId": "$questionId",
          "score": $score
        }
        """.trimIndent()

    private fun issueToken(): String {
        val email = "faith-other-${UUID.randomUUID()}@example.com"
        val password = "password123!"
        mockMvc.post("/api/v1/auth/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"$password","displayName":"${uniqueTestNickname()}"}"""
        }.andExpect { status { isCreated() } }
        val login = mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"$password"}"""
        }.andReturn()
        return objectMapper.readTree(login.response.contentAsString)["token"].asText()
    }
}
