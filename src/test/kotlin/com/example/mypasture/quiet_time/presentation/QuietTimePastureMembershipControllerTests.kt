package com.example.mypasture.quiet_time.presentation

import com.example.mypasture.auth.domain.uniqueTestNickname
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/** 고정 목장의 초대 만료·본명 삭제·승인 권한·단일 소속을 검증합니다. */
@SpringBootTest
@AutoConfigureMockMvc
class QuietTimePastureMembershipControllerTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val jdbcTemplate: JdbcTemplate,
) {
    @Test
    fun `목자 승인 시 본명을 삭제하고 목원은 다른 목장에 가입할 수 없다`() {
        val leader = account()
        val member = account()
        val outsider = account()
        val pasture = createPasture(leader)
        val otherPasture = createPasture(outsider)
        val invite = issueInvite(leader, pasture)
        val code = invite["code"].asText()
        check(invite["inviteUrl"].asText().endsWith("/pasture/join/$code"))
        val expiresAt = Instant.parse(invite["expiresAt"].asText())
        check(expiresAt.isAfter(Instant.now()) && expiresAt.isBefore(Instant.now().plusSeconds(61)))

        mockMvc.post("/api/v1/quiet-time/pastures/invites/resolve") {
            header("Authorization", "Bearer ${member.token}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"code":"$code"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(pasture) }
        }

        val request = join(member, code)
        mockMvc.get("/api/v1/quiet-time/pastures/current") {
            header("Authorization", "Bearer ${member.token}")
        }.andExpect { jsonPath("$.status") { value("PENDING") } }
        mockMvc.post("/api/v1/quiet-time/pastures") {
            header("Authorization", "Bearer ${member.token}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"새 목장","leaderName":"목자"}"""
        }.andExpect { status { isConflict() } }
        mockMvc.post("/api/v1/quiet-time/pastures/join-requests") {
            header("Authorization", "Bearer ${member.token}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"code":"${issueInvite(outsider, otherPasture)["code"].asText()}","realName":"홍길동"}"""
        }.andExpect { status { isConflict() } }

        mockMvc.get("/api/v1/quiet-time/pastures/$pasture/join-requests") {
            header("Authorization", "Bearer ${outsider.token}")
        }.andExpect { status { isForbidden() } }
        mockMvc.get("/api/v1/quiet-time/pastures/$pasture/join-requests") {
            header("Authorization", "Bearer ${leader.token}")
        }.andExpect {
            status { isOk() }
            jsonPath("$[0].id") { value(request) }
            jsonPath("$[0].realName") { value("홍길동") }
        }
        mockMvc.post("/api/v1/quiet-time/pastures/$pasture/join-requests/$request/approve") {
            header("Authorization", "Bearer ${outsider.token}")
        }.andExpect { status { isForbidden() } }
        mockMvc.post("/api/v1/quiet-time/pastures/$pasture/join-requests/$request/approve") {
            header("Authorization", "Bearer ${leader.token}")
        }.andExpect { status { isNoContent() } }
        check(jdbcTemplate.queryForObject("select real_name from quiet_time_pasture_join_requests where id = ?", String::class.java, UUID.fromString(request)) == null)

        mockMvc.get("/api/v1/quiet-time/pastures/current") {
            header("Authorization", "Bearer ${member.token}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("MEMBER") }
            jsonPath("$.pasture.id") { value(pasture) }
        }
        mockMvc.post("/api/v1/quiet-time/pastures") {
            header("Authorization", "Bearer ${member.token}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"다른 목장","leaderName":"목자"}"""
        }.andExpect { status { isConflict() } }
        issueInvite(member, pasture)
    }

    @Test
    fun `만료된 초대와 거절된 요청은 본명을 보관하지 않는다`() {
        val leader = account()
        val applicant = account()
        val pasture = createPasture(leader)
        val invite = issueInvite(leader, pasture)
        val code = invite["code"].asText()
        jdbcTemplate.update("update quiet_time_pasture_invites set expires_at = ? where code_hash = ?", Timestamp.from(Instant.now().minusSeconds(1)), hash(code))
        mockMvc.post("/api/v1/quiet-time/pastures/join-requests") {
            header("Authorization", "Bearer ${applicant.token}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"code":"$code","realName":"홍길동"}"""
        }.andExpect { status { isGone() } }

        val freshCode = issueInvite(leader, pasture)["code"].asText()
        val request = join(applicant, freshCode)
        mockMvc.post("/api/v1/quiet-time/pastures/$pasture/join-requests/$request/reject") {
            header("Authorization", "Bearer ${leader.token}")
        }.andExpect { status { isNoContent() } }
        check(jdbcTemplate.queryForObject("select real_name from quiet_time_pasture_join_requests where id = ?", String::class.java, UUID.fromString(request)) == null)
        mockMvc.get("/api/v1/quiet-time/pastures/current") {
            header("Authorization", "Bearer ${applicant.token}")
        }.andExpect { jsonPath("$.status") { value("NONE") } }

        val next = join(applicant, freshCode)
        jdbcTemplate.update("update quiet_time_pasture_join_requests set expires_at = ? where id = ?", Timestamp.from(Instant.now().minusSeconds(1)), UUID.fromString(next))
        mockMvc.post("/api/v1/quiet-time/pastures/$pasture/join-requests/$next/approve") {
            header("Authorization", "Bearer ${leader.token}")
        }.andExpect { status { isGone() } }
        mockMvc.get("/api/v1/quiet-time/pastures/current") {
            header("Authorization", "Bearer ${applicant.token}")
        }.andExpect { jsonPath("$.status") { value("NONE") } }
        check(jdbcTemplate.queryForObject("select real_name from quiet_time_pasture_join_requests where id = ?", String::class.java, UUID.fromString(next)) == null)
    }

    private fun createPasture(account: Account): String = objectMapper.readTree(
        mockMvc.post("/api/v1/quiet-time/pastures") {
            header("Authorization", "Bearer ${account.token}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"새 고정 목장","leaderName":"목자"}"""
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString,
    )["id"].asText()

    private fun issueInvite(account: Account, pastureId: String): JsonNode = objectMapper.readTree(
        mockMvc.post("/api/v1/quiet-time/pastures/$pastureId/invites") {
            header("Authorization", "Bearer ${account.token}")
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString,
    )

    private fun join(account: Account, code: String): String = objectMapper.readTree(
        mockMvc.post("/api/v1/quiet-time/pastures/join-requests") {
            header("Authorization", "Bearer ${account.token}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"code":"$code","realName":"홍길동"}"""
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString,
    )["id"].asText()

    private fun account(): Account {
        val email = "pasture-${UUID.randomUUID()}@example.com"
        mockMvc.post("/api/v1/auth/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"password123!","displayName":"${uniqueTestNickname()}"}"""
        }.andExpect { status { isCreated() } }
        val result = mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"password123!"}"""
        }.andExpect { status { isOk() } }.andReturn()
        return Account(objectMapper.readTree(result.response.contentAsString)["token"].asText())
    }

    private fun hash(code: String): String = java.util.HexFormat.of().formatHex(
        java.security.MessageDigest.getInstance("SHA-256").digest(code.toByteArray(Charsets.US_ASCII)),
    )

    private data class Account(val token: String)
}
