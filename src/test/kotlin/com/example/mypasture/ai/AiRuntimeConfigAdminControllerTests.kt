package com.example.mypasture.ai

import com.example.mypasture.auth.domain.uniqueTestNickname

import com.example.mypasture.ai.domain.AiUseCase
import com.example.mypasture.ai.infrastructure.AiRuntimeConfigVersionRepository
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID

/** AI 런타임 설정의 관리자 권한, 멱등 활성화, 변경 이력, 롤백을 검증합니다. */
@SpringBootTest
@AutoConfigureMockMvc
class AiRuntimeConfigAdminControllerTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @Autowired private val versionRepository: AiRuntimeConfigVersionRepository,
) {
    @Test
    fun `일반 사용자는 실행 중인 모델 설정을 조회하거나 변경할 수 없다`() {
        mockMvc.get("/api/v1/admin/ai/runtime-config") {
            header("Authorization", "Bearer ${createUserToken("일반 사용자")}")
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.code") { value("ACCESS_DENIED") }
        }
    }

    @Test
    fun `관리자 설정 활성화는 멱등하고 롤백은 새 불변 버전을 만든다`() {
        val operationId = UUID.randomUUID()
        val before = versionRepository.count()
        val activationBody = activationRequest(operationId, "gpt-5.6-terra", "gpt-5.6-luna", "운영 품질과 비용 균형 설정 활성화")

        val activated = repeatRequest("/api/v1/admin/ai/runtime-config/activate", activationBody)
        val activatedId = activated["config"]["id"].asText()
        assertEquals(before + 1, versionRepository.count())
        assertEquals(true, activated["active"].asBoolean())
        assertEquals("gpt-5.6-terra", activated["config"]["generatorModel"].asText())

        val rollbackResult = postJson(
            "/api/v1/admin/ai/runtime-config/rollback",
            mapOf(
                "operationId" to UUID.randomUUID(),
                "targetVersionId" to activatedId,
                "reason" to "검증된 이전 설정을 새 버전으로 복원",
            ),
        )
        assertEquals(before + 2, versionRepository.count())
        assertEquals(activatedId, rollbackResult["previousVersionId"].asText())

        val overview = mockMvc.get("/api/v1/admin/ai/runtime-config") {
            header("Authorization", "Bearer ${adminToken()}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.current.id") { value(rollbackResult["config"]["id"].asText()) }
            jsonPath("$.history.length()") { value(versionRepository.countByUseCase(AiUseCase.AI_PASTOR).toInt().coerceAtMost(20)) }
        }.andReturn()
        val overviewJson = objectMapper.readTree(overview.response.contentAsString)
        check(overviewJson["models"].any { it["modelId"].asText() == "gpt-5.6-luna" })

        val auditCount = jdbcTemplate.queryForObject(
            "select count(*) from admin_audit_logs where operation_id = ?",
            Long::class.java,
            operationId,
        )
        assertEquals(1L, auditCount ?: 0L)
    }

    @Test
    fun `서버 허용 목록 밖의 모델은 관리자가 활성화할 수 없다`() {
        mockMvc.post("/api/v1/admin/ai/runtime-config/activate") {
            header("Authorization", "Bearer ${adminToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = activationRequest(UUID.randomUUID(), "arbitrary-model", "gpt-5.6-luna", "허용되지 않은 모델 차단 검증")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("AI_INVALID_RUNTIME_CONFIG") }
        }
    }

    @Test
    fun `오늘의 말씀 모델 설정은 AI 목자 설정과 분리된다`() {
        val activated = postBody(
            "/api/v1/admin/ai/runtime-config/activate",
            activationRequest(
                UUID.randomUUID(),
                "gpt-5.4-mini",
                "gpt-5.4-nano",
                "오늘의 말씀 생성 비용 최적화 설정",
                "DAILY_WORD",
            ),
        )

        mockMvc.get("/api/v1/admin/ai/runtime-config") {
            header("Authorization", "Bearer ${adminToken()}")
            param("useCase", "DAILY_WORD")
        }.andExpect {
            status { isOk() }
            jsonPath("$.current.id") { value(activated["config"]["id"].asText()) }
            jsonPath("$.current.useCase") { value("DAILY_WORD") }
            jsonPath("$.current.generatorModel") { value("gpt-5.4-mini") }
            jsonPath("$.history[0].config.useCase") { value("DAILY_WORD") }
        }

        mockMvc.get("/api/v1/admin/ai/runtime-config") {
            header("Authorization", "Bearer ${adminToken()}")
            param("useCase", "AI_PASTOR")
        }.andExpect {
            status { isOk() }
            jsonPath("$.current.useCase") { value("AI_PASTOR") }
        }
    }

    private fun repeatRequest(path: String, body: String): JsonNode {
        var result: JsonNode? = null
        repeat(2) {
            result = objectMapper.readTree(
                mockMvc.post(path) {
                    header("Authorization", "Bearer ${adminToken()}")
                    contentType = MediaType.APPLICATION_JSON
                    content = body
                }.andExpect { status { isOk() } }.andReturn().response.contentAsString,
            )
        }
        return result ?: error("No response")
    }

    private fun postJson(path: String, body: Map<String, Any>): JsonNode = objectMapper.readTree(
        mockMvc.post(path) {
            header("Authorization", "Bearer ${adminToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(body)
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString,
    )

    private fun postBody(path: String, body: String): JsonNode = objectMapper.readTree(
        mockMvc.post(path) {
            header("Authorization", "Bearer ${adminToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString,
    )

    private fun activationRequest(
        operationId: UUID,
        generator: String,
        evaluator: String,
        reason: String,
        useCase: String = "AI_PASTOR",
    ) =
        objectMapper.writeValueAsString(
            mapOf(
                "operationId" to operationId,
                "useCase" to useCase,
                "generatorModel" to generator,
                "evaluatorModel" to evaluator,
                "generatorReasoningEffort" to "LOW",
                "evaluatorReasoningEffort" to "MEDIUM",
                "maxOutputTokens" to 600,
                "evaluationMaxOutputTokens" to 250,
                "evaluationEnabled" to true,
                "evaluationThreshold" to 4,
                "maxRevisionCount" to 1,
                "reason" to reason,
            ),
        )

    private fun adminToken(): String {
        val email = signup("운영관리자")
        jdbcTemplate.update("update users set role = 'ADMIN' where email = ?", email)
        return login(email)
    }

    private fun createUserToken(displayName: String): String = login(signup(displayName))

    private fun signup(displayName: String): String {
        val email = "ai-admin-${UUID.randomUUID()}@example.com"
        mockMvc.post("/api/v1/auth/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                mapOf("email" to email, "password" to DEFAULT_PASSWORD, "displayName" to uniqueTestNickname(displayName)),
            )
        }.andExpect { status { isCreated() } }
        return email
    }

    private fun login(email: String): String {
        val response = mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("email" to email, "password" to DEFAULT_PASSWORD))
        }.andExpect { status { isOk() } }.andReturn()
        return objectMapper.readTree(response.response.contentAsString)["token"].asText()
    }

    companion object {
        private const val DEFAULT_PASSWORD = "password123!"
    }
}
