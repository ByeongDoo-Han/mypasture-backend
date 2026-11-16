package com.example.mypasture.ai

import com.example.mypasture.admin_audit.domain.AdminAuditAction
import com.example.mypasture.admin_audit.infrastructure.AdminAuditLogRepository
import com.example.mypasture.ai.domain.AiAnswerHistoryEntity
import com.example.mypasture.ai.domain.GroundedAnswer
import com.example.mypasture.ai.infrastructure.AiAnswerHistoryCipher
import com.example.mypasture.ai.infrastructure.AiAnswerHistoryRepository
import com.example.mypasture.auth.infrastructure.JwtTokenProvider
import com.example.mypasture.auth.infrastructure.UserRepository
import com.example.mypasture.user.domain.UserEntity
import com.example.mypasture.user.domain.UserRole
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** 관리자만 AI 원문을 사유와 감사 기록을 남긴 뒤 볼 수 있는지 검증합니다. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AiHistoryTestConfiguration::class)
@TestPropertySource(properties = ["app.bible.kor1910-html-path=src/test/resources/bible/kor_html", "app.bible.import-enabled=true", "app.ai.mock-enabled=true"])
class AiAdminHistoryControllerTests(
    @Autowired private val mvc: MockMvc,
    @Autowired private val users: UserRepository,
    @Autowired private val records: AiAnswerHistoryRepository,
    @Autowired private val cipher: AiAnswerHistoryCipher,
    @Autowired private val jwt: JwtTokenProvider,
    @Autowired private val audits: AdminAuditLogRepository,
    @Autowired private val mapper: ObjectMapper,
) {
    @Test
    fun `관리자만 보존 기간 안의 질문 목록과 감사 기록이 남는 답변 상세를 볼 수 있다`() {
        val admin = users.save(UserEntity(email = "admin-${UUID.randomUUID()}@example.com", displayName = "관리자", role = UserRole.ADMIN))
        val owner = users.save(UserEntity(email = "owner-${UUID.randomUUID()}@example.com", displayName = "질문자"))
        val normal = users.save(UserEntity(email = "normal-${UUID.randomUUID()}@example.com", displayName = "일반"))
        val current = record(owner, "비공개 질문", "비공개 답변", Instant.now())
        record(owner, "만료 질문", "만료 답변", Instant.now().minus(366, ChronoUnit.DAYS))
        val deleted = users.save(UserEntity(email = "deleted-${UUID.randomUUID()}@example.com", displayName = "탈퇴 예정"))
        record(deleted, "삭제 질문", "삭제 답변", Instant.now())
        deleted.withdraw(Instant.now())
        users.save(deleted)
        val path = "/api/v1/admin/ai/history"

        mvc.get(path).andExpect { status { isUnauthorized() } }
        mvc.get(path) { header("Authorization", bearer(normal)) }.andExpect { status { isForbidden() } }
        mvc.post("$path/${current.turnId}/review") {
            header("Authorization", bearer(normal))
            contentType = MediaType.APPLICATION_JSON
            content = """{"reason":"QUALITY_REVIEW"}"""
        }.andExpect { status { isForbidden() } }

        val list = mvc.get("$path?page=0&size=50") {
            header("Authorization", bearer(admin))
        }.andExpect {
            status { isOk() }
            header { string("Cache-Control", "no-store") }
            jsonPath("$.items[0].turnId") { value(current.turnId.toString()) }
        }.andReturn()
        val items = mapper.readTree(list.response.contentAsString).path("items")
        assertEquals(1, items.size())
        assertEquals(owner.email, items[0].path("email").asText())
        assertEquals(owner.displayName, items[0].path("displayName").asText())
        assertEquals("비공개 질문", items[0].path("questionPreview").asText())
        assertFalse(list.response.contentAsString.contains("비공개 답변"))
        assertEquals(1, audits.findTop20ByTargetTypeAndTargetIdOrderByCreatedAtDesc("AI_HISTORY_LIST", admin.id).size)

        val detail = mvc.post("$path/${current.turnId}/review") {
            header("Authorization", bearer(admin))
            contentType = MediaType.APPLICATION_JSON
            content = """{"reason":"QUALITY_REVIEW"}"""
        }.andExpect {
            status { isOk() }
            header { string("Cache-Control", "no-store") }
            jsonPath("$.history.question") { value("비공개 질문") }
            jsonPath("$.history.versions[0].answer") { value("비공개 답변") }
        }.andReturn()
        assertEquals(owner.id.toString(), mapper.readTree(detail.response.contentAsString).path("userId").asText())
        val targetAudits = audits.findTop20ByTargetTypeAndTargetIdOrderByCreatedAtDesc("AI_HISTORY", current.turnId)
        assertEquals(1, targetAudits.size)
        assertEquals(AdminAuditAction.AI_HISTORY_DETAIL_VIEWED, targetAudits.single().action)
        assertEquals(admin.id, targetAudits.single().adminUserId)
        assertEquals("reason=QUALITY_REVIEW", targetAudits.single().details)
        assertFalse(targetAudits.single().details.contains("비공개 질문"))
    }

    @Test
    fun `잘못된 페이지와 없는 기록을 조회해도 상세 조회 감사 기록을 만들지 않는다`() {
        val admin = users.save(UserEntity(email = "admin-${UUID.randomUUID()}@example.com", displayName = "관리자", role = UserRole.ADMIN))
        val owner = users.save(UserEntity(email = "owner-${UUID.randomUUID()}@example.com", displayName = "질문자"))
        val expired = record(owner, "오래된 질문", "오래된 답변", Instant.now().minus(366, ChronoUnit.DAYS))
        mvc.get("/api/v1/admin/ai/history?size=51") {
            header("Authorization", bearer(admin))
        }.andExpect { status { isBadRequest() }; header { string("Cache-Control", "no-store") } }
        val missing = UUID.randomUUID()
        mvc.post("/api/v1/admin/ai/history/$missing/review") {
            header("Authorization", bearer(admin))
            contentType = MediaType.APPLICATION_JSON
            content = """{"reason":"SAFETY_REVIEW"}"""
        }.andExpect { status { isNotFound() }; header { string("Cache-Control", "no-store") } }
        mvc.post("/api/v1/admin/ai/history/${expired.turnId}/review") {
            header("Authorization", bearer(admin))
            contentType = MediaType.APPLICATION_JSON
            content = """{"reason":"SAFETY_REVIEW"}"""
        }.andExpect { status { isNotFound() } }
        assertEquals(0, audits.findTop20ByTargetTypeAndTargetIdOrderByCreatedAtDesc("AI_HISTORY", missing).size)
        assertEquals(0, audits.findTop20ByTargetTypeAndTargetIdOrderByCreatedAtDesc("AI_HISTORY", expired.turnId).size)
    }

    private fun bearer(user: UserEntity) = "Bearer ${jwt.issueAccessToken(user).accessToken}"

    private fun record(user: UserEntity, question: String, answer: String, createdAt: Instant): AiAnswerHistoryEntity =
        records.save(AiAnswerHistoryEntity(
            turnId = UUID.randomUUID(), userId = user.id, parentTurnId = null, conversationId = null,
            questionCiphertext = cipher.encrypt(question),
            responseCiphertext = cipher.encrypt(GroundedAnswer(null, answer = answer, citations = emptyList(), promptTokens = 0, completionTokens = 0, estimatedCostUsd = BigDecimal.ZERO, cacheHit = false)),
            modelConfigVersionId = null, modelVersionNumber = null, generatorModel = null,
            assistantVersion = "test", responseSchemaVersion = "test", createdAt = createdAt,
        ))

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun postgres(registry: DynamicPropertyRegistry) = AiHistoryTestConfiguration.postgres(registry)
    }
}
