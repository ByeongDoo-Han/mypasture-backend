package com.example.mypasture.auth.presentation

import com.example.mypasture.auth.application.AdminEmailDeliveryException
import com.example.mypasture.auth.application.AdminEmailLoginSender
import com.example.mypasture.auth.application.AdminEmailLoginService
import com.example.mypasture.auth.application.AuthService
import com.example.mypasture.auth.application.InvalidAdminEmailCodeException
import com.example.mypasture.auth.infrastructure.AdminEmailLoginStateRepository
import com.example.mypasture.auth.infrastructure.JwtTokenProvider
import com.example.mypasture.auth.infrastructure.UserRepository
import com.example.mypasture.auth.presentation.dto.AdminEmailLoginConfirmRequest
import com.example.mypasture.auth.presentation.dto.AdminEmailLoginRequest
import com.example.mypasture.user.domain.UserEntity
import com.example.mypasture.user.domain.UserRole
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** 실제 DB 트랜잭션으로 관리자 OTP의 제한·일회성 소비·권한 재검증을 검증합니다. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = [
    "app.auth.admin-email-login.enabled=true",
    "app.auth.admin-email-login.hmac-secret=admin-email-test-key-at-least-thirty-two-bytes",
])
class AdminEmailLoginControllerTests(
    @Autowired private val service: AdminEmailLoginService,
    @Autowired private val authService: AuthService,
    @Autowired private val users: UserRepository,
    @Autowired private val states: AdminEmailLoginStateRepository,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val mvc: MockMvc,
    @Autowired private val mapper: ObjectMapper,
) {
    // SMTP 없이 enabled 경로를 시험하며 운영 코드의 로그 발송 대체는 허용하지 않습니다.
    @MockitoBean private lateinit var sender: AdminEmailLoginSender
    @MockitoSpyBean private lateinit var jwt: JwtTokenProvider
    private val codes = ConcurrentHashMap<String, String>()
    private val deliveries = AtomicInteger()
    private val failDelivery = AtomicBoolean()

    @BeforeEach
    fun captureDelivery() {
        doAnswer { invocation ->
            codes[invocation.getArgument(0)] = invocation.getArgument(1)
            deliveries.incrementAndGet()
            if (failDelivery.get()) throw AdminEmailDeliveryException()
            null
        }.`when`(sender).send(anyString(), anyString(), any(Instant::class.java) ?: Instant.EPOCH)
    }

    @Test
    fun `public request confirms admin session only once and persists HMAC without plaintext`() {
        val admin = admin()
        val response = mvc.post("/api/v1/auth/admin-email-login/requests") {
            contentType = MediaType.APPLICATION_JSON
            content = mapper.writeValueAsString(mapOf("email" to admin.email))
        }.andExpect {
            status { isOk() }
            header { string("Cache-Control", "no-store") }
            jsonPath("$.expiresInSeconds") { value(300) }
            jsonPath("$.resendAfterSeconds") { value(60) }
        }.andReturn().response.contentAsString
        val challenge = UUID.fromString(mapper.readTree(response)["challengeId"].asText())
        val code = codes.getValue(admin.email!!)
        assertEquals(64, states.findById(admin.id).orElseThrow().codeHash!!.length)
        assertNotEquals(code, states.findById(admin.id).orElseThrow().codeHash)
        assertEquals(0, sessions(admin.id))
        mvc.post("/api/v1/auth/admin-email-login/confirm") {
            contentType = MediaType.APPLICATION_JSON
            content = mapper.writeValueAsString(mapOf("challengeId" to challenge, "code" to code))
        }.andExpect {
            status { isOk() }
            header { string("Cache-Control", "no-store") }
            jsonPath("$.user.role") { value("ADMIN") }
            jsonPath("$.token") { isString() }
            jsonPath("$.refreshToken") { isString() }
        }
        assertEquals(1, sessions(admin.id))
        assertNull(states.findById(admin.id).orElseThrow().codeHash)
        assertFailsWith<InvalidAdminEmailCodeException> { confirm(challenge, code) }
        assertEquals(1, sessions(admin.id))
    }

    @Test
    fun `unknown non-admin and deleted accounts return same request contract without mail or session`() {
        val normal = users.saveAndFlush(UserEntity(email = uniqueEmail(), displayName = "Member"))
        val deleted = users.saveAndFlush(UserEntity(email = uniqueEmail(), displayName = "Deleted", role = UserRole.ADMIN, deletedAt = Instant.now()))
        for (email in listOf(uniqueEmail(), normal.email!!, deleted.email!!)) {
            val response = service.request(AdminEmailLoginRequest(email))
            assertEquals(300, response.expiresInSeconds)
            assertEquals(60, response.resendAfterSeconds)
            assertFailsWith<InvalidAdminEmailCodeException> { confirm(response.challengeId, "000000") }
        }
        assertEquals(0, deliveries.get())
        assertEquals(0, sessions(normal.id))
        assertEquals(0, sessions(deleted.id))
    }

    @Test
    fun `five failures commit across separate rejected transactions and cannot be reset by resend`() {
        val user = admin()
        val first = request(user)
        repeat(3) { assertFailsWith<InvalidAdminEmailCodeException> { confirm(first, wrongCode(user)) } }
        assertEquals(3, states.findById(user.id).orElseThrow().failedAttempts)
        allowResend(user)
        val second = request(user)
        assertNotEquals(first, second)
        assertEquals(3, states.findById(user.id).orElseThrow().failedAttempts)
        repeat(2) { assertFailsWith<InvalidAdminEmailCodeException> { confirm(second, wrongCode(user)) } }
        assertEquals(5, states.findById(user.id).orElseThrow().failedAttempts)
        assertFailsWith<InvalidAdminEmailCodeException> { confirm(second, codes.getValue(user.email!!)) }
        allowResend(user)
        request(user)
        assertEquals(2, deliveries.get())
        assertEquals(0, sessions(user.id))
    }

    @Test
    fun `expired failure window allows retry without silently reviving an expired code`() {
        val user = admin()
        val id = request(user)
        repeat(5) { assertFailsWith<InvalidAdminEmailCodeException> { confirm(id, wrongCode(user)) } }
        jdbc.update("update admin_email_login_states set failure_window_started_at = ?, expires_at = ? where user_id = ?",
            java.sql.Timestamp.from(Instant.now().minusSeconds(601)), java.sql.Timestamp.from(Instant.now().minusSeconds(1)), user.id)
        assertFailsWith<InvalidAdminEmailCodeException> { confirm(id, codes.getValue(user.email!!)) }
        assertEquals(1, states.findById(user.id).orElseThrow().failedAttempts)
        allowResend(user)
        val replacement = request(user)
        assertEquals(user.id, confirm(replacement, codes.getValue(user.email!!)).user.id)
    }

    @Test
    fun `resend has a sixty second cooldown and five deliveries per hour`() {
        val user = admin()
        request(user)
        request(user)
        assertEquals(1, deliveries.get())
        repeat(4) { allowResend(user); request(user) }
        assertEquals(5, deliveries.get())
        allowResend(user)
        request(user)
        assertEquals(5, deliveries.get())
        jdbc.update("update admin_email_login_states set send_window_started_at = ? where user_id = ?",
            java.sql.Timestamp.from(Instant.now().minusSeconds(3601)), user.id)
        request(user)
        assertEquals(6, deliveries.get())
    }

    @Test
    fun `resend invalidates older challenge and binds HMAC to each new challenge`() {
        val user = admin()
        val old = request(user)
        val oldCode = codes.getValue(user.email!!)
        val oldHash = states.findById(user.id).orElseThrow().codeHash
        allowResend(user)
        val next = request(user)
        assertNotEquals(oldHash, states.findById(user.id).orElseThrow().codeHash)
        assertFailsWith<InvalidAdminEmailCodeException> { confirm(old, oldCode) }
        assertEquals(user.id, confirm(next, codes.getValue(user.email!!)).user.id)
    }

    @Test
    fun `SMTP failure returns generic response invalidates code and persists quota before retry`() {
        val user = admin()
        val previous = request(user)
        val previousCode = codes.getValue(user.email!!)
        allowResend(user)
        failDelivery.set(true)
        val failed = request(user)
        val attemptedCode = codes.getValue(user.email!!)
        assertNull(states.findById(user.id).orElseThrow().codeHash)
        assertEquals(2, states.findById(user.id).orElseThrow().sendCount)
        assertFailsWith<InvalidAdminEmailCodeException> { confirm(previous, previousCode) }
        assertFailsWith<InvalidAdminEmailCodeException> { confirm(failed, attemptedCode) }
        request(user)
        assertEquals(2, deliveries.get())
        failDelivery.set(false)
        allowResend(user)
        val retried = request(user)
        assertEquals(user.id, confirm(retried, codes.getValue(user.email!!)).user.id)
    }

    @Test
    fun `role revocation authVersion changes and deletion all invalidate pending codes`() {
        for (change in listOf("role = 'USER'", "auth_version = auth_version + 1", "deleted_at = CURRENT_TIMESTAMP")) {
            val user = admin()
            val challenge = request(user)
            jdbc.update("update users set $change where id = ?", user.id)
            assertFailsWith<InvalidAdminEmailCodeException> { confirm(challenge, codes.getValue(user.email!!)) }
            assertEquals(0, sessions(user.id))
        }
    }

    @Test
    fun `two simultaneous confirmations create exactly one session`() {
        val user = admin()
        val challenge = request(user)
        val code = codes.getValue(user.email!!)
        val results = concurrently {
            try { confirm(challenge, code); true } catch (_: InvalidAdminEmailCodeException) { false }
        }
        assertEquals(1, results.count { it })
        assertEquals(1, sessions(user.id))
        assertNotNull(states.findById(user.id).orElseThrow().consumedAt)
    }

    @Test
    fun `token generation failure rolls back both refresh session and challenge consumption`() {
        val user = admin()
        val challenge = request(user)
        doThrow(IllegalStateException("Synthetic token generation failure"))
            .`when`(jwt).issueAccessToken(any(UserEntity::class.java) ?: user)
        assertFailsWith<IllegalStateException> { confirm(challenge, codes.getValue(user.email!!)) }
        assertEquals(0, sessions(user.id))
        val state = states.findById(user.id).orElseThrow()
        assertNull(state.consumedAt)
        assertNotNull(state.codeHash)
    }

    @Test
    fun `admin session helper cannot issue outside its callers transaction`() {
        val user = admin()
        assertFailsWith<IllegalTransactionStateException> { authService.issueAdminEmailSession(user.id, user.authVersion) }
        assertEquals(0, sessions(user.id))
    }

    @Test
    fun `simultaneous initial requests share persistent cooldown without duplicate row or mail`() {
        val user = admin()
        concurrently { request(user) }
        assertEquals(1, deliveries.get())
        assertEquals(1, states.findById(user.id).orElseThrow().sendCount)
    }

    @Test
    fun `confirmation failures use same public unauthorized error without token`() {
        mvc.post("/api/v1/auth/admin-email-login/confirm") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"challengeId":"${UUID.randomUUID()}","code":"123456"}"""
        }.andExpect {
            status { isUnauthorized() }
            header { string("Cache-Control", "no-store") }
            jsonPath("$.code") { value("INVALID_ADMIN_EMAIL_CODE") }
            jsonPath("$.token") { doesNotExist() }
        }
    }

    private fun admin() = users.saveAndFlush(UserEntity(email = uniqueEmail(), displayName = "Admin Test", role = UserRole.ADMIN))
    private fun uniqueEmail() = "admin-${UUID.randomUUID()}@example.test"
    private fun request(user: UserEntity) = service.request(AdminEmailLoginRequest(user.email!!)).challengeId
    private fun confirm(id: UUID, code: String) = service.confirm(AdminEmailLoginConfirmRequest(id, code))
    private fun wrongCode(user: UserEntity) = if (codes[user.email] == "000000") "111111" else "000000"
    private fun sessions(userId: UUID) = jdbc.queryForObject("select count(*) from auth_refresh_tokens where user_id = ?", Int::class.java, userId)
    private fun allowResend(user: UserEntity) {
        jdbc.update("update admin_email_login_states set last_sent_at = ? where user_id = ?",
            java.sql.Timestamp.from(Instant.now().minusSeconds(61)), user.id)
    }
    private fun <T> concurrently(action: () -> T): List<T> {
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        return try {
            val futures = (1..2).map { executor.submit<T> { start.await(5, TimeUnit.SECONDS); action() } }
            start.countDown()
            futures.map { it.get(20, TimeUnit.SECONDS) }
        } finally { executor.shutdownNow() }
    }

    companion object {
        /** 환경변수 지정 시 동일한 트랜잭션 시험을 실제 PostgreSQL/Flyway 위에서 실행합니다. */
        @JvmStatic
        @DynamicPropertySource
        fun postgres(registry: DynamicPropertyRegistry) {
            val url = System.getenv("ADMIN_EMAIL_LOGIN_TEST_JDBC_URL")?.takeIf { it.isNotBlank() } ?: return
            registry.add("spring.datasource.url") { url }
            registry.add("spring.datasource.username") { System.getenv("ADMIN_EMAIL_LOGIN_TEST_JDBC_USER") ?: "postgres" }
            registry.add("spring.datasource.password") { System.getenv("ADMIN_EMAIL_LOGIN_TEST_JDBC_PASSWORD") ?: "" }
            registry.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
            registry.add("spring.flyway.enabled") { "true" }
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
        }
    }
}
