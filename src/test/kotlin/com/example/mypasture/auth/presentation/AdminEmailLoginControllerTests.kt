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
    fun `공개 요청은 관리자 세션을 한 번만 확인하고 평문 없이 HMAC을 저장한다`() {
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
    fun `없는 계정 비관리자 삭제 계정은 메일과 세션 없이 같은 요청 응답을 반환한다`() {
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
    fun `별도 거부 트랜잭션의 실패 5회는 확정되고 재전송으로 초기화되지 않는다`() {
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
    fun `만료된 실패 시간 창은 만료 코드를 되살리지 않고 재시도를 허용한다`() {
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
    fun `재전송에는 60초 대기와 시간당 5회 발송 제한을 적용한다`() {
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
    fun `재전송은 이전 확인 코드를 무효화하고 새 코드마다 HMAC을 묶는다`() {
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
    fun `SMTP 실패는 일반 응답을 반환하고 코드를 무효화하며 재시도 전에 사용량을 저장한다`() {
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
    fun `역할 박탈 인증 버전 변경 계정 삭제는 대기 중인 코드를 모두 무효화한다`() {
        for (change in listOf("role = 'USER'", "auth_version = auth_version + 1", "deleted_at = CURRENT_TIMESTAMP")) {
            val user = admin()
            val challenge = request(user)
            jdbc.update("update users set $change where id = ?", user.id)
            assertFailsWith<InvalidAdminEmailCodeException> { confirm(challenge, codes.getValue(user.email!!)) }
            assertEquals(0, sessions(user.id))
        }
    }

    @Test
    fun `동시 확인 요청 두 건은 세션을 정확히 한 건만 만든다`() {
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
    fun `토큰 생성 실패는 갱신 세션과 코드 소비를 함께 롤백한다`() {
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
    fun `관리자 세션 헬퍼는 호출자 트랜잭션 밖에서 발급할 수 없다`() {
        val user = admin()
        assertFailsWith<IllegalTransactionStateException> { authService.issueAdminEmailSession(user.id, user.authVersion) }
        assertEquals(0, sessions(user.id))
    }

    @Test
    fun `동시 최초 요청은 영속적인 대기를 공유하고 행이나 메일을 중복 생성하지 않는다`() {
        val user = admin()
        concurrently { request(user) }
        assertEquals(1, deliveries.get())
        assertEquals(1, states.findById(user.id).orElseThrow().sendCount)
    }

    @Test
    fun `확인 실패는 토큰 없이 같은 공개 인증 실패 응답을 반환한다`() {
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
