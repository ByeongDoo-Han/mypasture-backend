package com.example.mypasture.user.presentation

import com.example.mypasture.auth.infrastructure.JwtTokenProvider
import com.example.mypasture.auth.infrastructure.UserRepository
import com.example.mypasture.user.application.AvatarPlatformService
import com.example.mypasture.user.domain.UserEntity
import com.example.mypasture.user.infrastructure.UserAvatarPlatformRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 실제 저장소로 발판 기본값, 계정 격리, 입력 거부와 동시 첫 선택을 검증합니다. */
@SpringBootTest
@AutoConfigureMockMvc
class AvatarPlatformControllerTests(
    @Autowired private val mvc: MockMvc,
    @Autowired private val users: UserRepository,
    @Autowired private val jwt: JwtTokenProvider,
    @Autowired private val service: AvatarPlatformService,
    @Autowired private val platforms: UserAvatarPlatformRepository,
) {
    @Test fun `인증한 소유자만 발판 아이템을 조회하고 선택할 수 있다`() {
        val first = users.save(UserEntity(displayName = "발판테스트${System.nanoTime()}"))
        val second = users.save(UserEntity(displayName = "다른발판${System.nanoTime()}"))
        val firstToken = jwt.issueAccessToken(first).accessToken
        val secondToken = jwt.issueAccessToken(second).accessToken

        mvc.get("/api/v1/avatar/platform").andExpect { status { isUnauthorized() } }
        mvc.put("/api/v1/avatar/platform") { contentType = MediaType.APPLICATION_JSON; content = """{"itemCode":"WOOD"}""" }
            .andExpect { status { isUnauthorized() } }
        mvc.get("/api/v1/avatar/platform") { header("Authorization", "Bearer $firstToken") }
            .andExpect { status { isOk() }; jsonPath("$.selectedItemCode") { value("GRASS") }; jsonPath("$.items.length()") { value(3) } }

        mvc.put("/api/v1/avatar/platform?userId=${second.id}") {
            header("Authorization", "Bearer $firstToken")
            contentType = MediaType.APPLICATION_JSON
            content = """{"itemCode":"WOOD"}"""
        }.andExpect { status { isOk() }; jsonPath("$.selectedItemCode") { value("WOOD") } }
        mvc.get("/api/v1/avatar/platform") { header("Authorization", "Bearer $secondToken") }
            .andExpect { status { isOk() }; jsonPath("$.selectedItemCode") { value("GRASS") } }
        mvc.get("/api/v1/avatar/platform") { header("Authorization", "Bearer $firstToken") }
            .andExpect { status { isOk() }; jsonPath("$.selectedItemCode") { value("WOOD") } }

        mvc.put("/api/v1/avatar/platform") {
            header("Authorization", "Bearer $firstToken")
            contentType = MediaType.APPLICATION_JSON
            content = """{"itemCode":"UNOWNED"}"""
        }.andExpect { status { isBadRequest() } }
        assertEquals("WOOD", platforms.findById(first.id).orElseThrow().selectedCode.name)
        assertTrue(platforms.findById(second.id).isEmpty)
    }

    @Test fun `동시 최초 선택은 설정 행을 한 건만 유지한다`() {
        val user = users.save(UserEntity(displayName = "동시발판${System.nanoTime()}"))
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val results = listOf("WOOD", "STONE").map { code ->
                executor.submit<String> { start.await(); service.select(user.id, code).selected.name }
            }
            start.countDown()
            assertEquals(setOf("WOOD", "STONE"), results.map { it.get(20, TimeUnit.SECONDS) }.toSet())
            assertEquals(1, platforms.findAll().count { it.userId == user.id })
            assertTrue(platforms.findById(user.id).orElseThrow().selectedCode.name in setOf("WOOD", "STONE"))
        } finally {
            executor.shutdownNow()
        }
    }

    companion object {
        @JvmStatic @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            val url = System.getenv("AVATAR_PLATFORM_TEST_JDBC_URL") ?: return
            registry.add("spring.datasource.url") { url }
            registry.add("spring.datasource.username") { System.getenv("AVATAR_PLATFORM_TEST_JDBC_USER") ?: "postgres" }
            registry.add("spring.datasource.password") { System.getenv("AVATAR_PLATFORM_TEST_JDBC_PASSWORD") ?: "" }
            registry.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
            registry.add("spring.flyway.enabled") { "true" }
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
        }
    }
}
