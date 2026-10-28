package com.example.mypasture.user.presentation

import com.example.mypasture.auth.infrastructure.JwtTokenProvider
import com.example.mypasture.auth.infrastructure.UserRepository
import com.example.mypasture.user.application.AvatarOutfitService
import com.example.mypasture.user.domain.UserEntity
import com.example.mypasture.user.infrastructure.UserAvatarOutfitRepository
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

/** 실제 저장소로 기본 복장, 계정 격리, 입력 거부, 장착 해제와 첫 장착 경합을 검증합니다. */
@SpringBootTest
@AutoConfigureMockMvc
class AvatarOutfitControllerTests(
    @Autowired private val mvc: MockMvc,
    @Autowired private val users: UserRepository,
    @Autowired private val jwt: JwtTokenProvider,
    @Autowired private val service: AvatarOutfitService,
    @Autowired private val outfits: UserAvatarOutfitRepository,
) {
    @Test fun `인증한 소유자만 맞는 아이템을 장착하고 해제할 수 있다`() {
        val first = users.save(UserEntity(displayName = "꾸미기테스트${System.nanoTime()}"))
        val second = users.save(UserEntity(displayName = "다른꾸미기${System.nanoTime()}"))
        val firstToken = jwt.issueAccessToken(first).accessToken
        val secondToken = jwt.issueAccessToken(second).accessToken

        mvc.get("/api/v1/avatar/outfit").andExpect { status { isUnauthorized() } }
        mvc.put("/api/v1/avatar/outfit") { contentType = MediaType.APPLICATION_JSON; content = """{"slot":"HAT","itemCode":"STRAW_HAT"}""" }
            .andExpect { status { isUnauthorized() } }
        mvc.get("/api/v1/avatar/outfit") { header("Authorization", "Bearer $firstToken") }
            .andExpect { status { isOk() }; jsonPath("$.equipped.HAT") { doesNotExist() }; jsonPath("$.items.length()") { value(14) } }

        mvc.put("/api/v1/avatar/outfit") {
            header("Authorization", "Bearer $firstToken")
            contentType = MediaType.APPLICATION_JSON
            content = """{"slot":"TOP","itemCode":"TAN_ZIP_JACKET"}"""
        }.andExpect { status { isOk() }; jsonPath("$.equipped.TOP") { value("TAN_ZIP_JACKET") } }

        mvc.put("/api/v1/avatar/outfit?userId=${second.id}") {
            header("Authorization", "Bearer $firstToken")
            contentType = MediaType.APPLICATION_JSON
            content = """{"slot":"HAT","itemCode":"STRAW_HAT"}"""
        }.andExpect { status { isOk() }; jsonPath("$.equipped.HAT") { value("STRAW_HAT") } }
        mvc.get("/api/v1/avatar/outfit") { header("Authorization", "Bearer $secondToken") }
            .andExpect { status { isOk() }; jsonPath("$.equipped.HAT") { doesNotExist() } }

        mvc.put("/api/v1/avatar/outfit") {
            header("Authorization", "Bearer $firstToken")
            contentType = MediaType.APPLICATION_JSON
            content = """{"slot":"GLASSES","itemCode":"STRAW_HAT"}"""
        }.andExpect { status { isBadRequest() } }
        mvc.put("/api/v1/avatar/outfit") {
            header("Authorization", "Bearer $firstToken")
            contentType = MediaType.APPLICATION_JSON
            content = """{"slot":"UNKNOWN","itemCode":null}"""
        }.andExpect { status { isBadRequest() } }
        assertEquals("STRAW_HAT", outfits.findById(first.id).orElseThrow().hatCode)

        mvc.put("/api/v1/avatar/outfit") {
            header("Authorization", "Bearer $firstToken")
            contentType = MediaType.APPLICATION_JSON
            content = """{"slot":"HAT","itemCode":null}"""
        }.andExpect { status { isOk() }; jsonPath("$.equipped.HAT") { doesNotExist() } }
        mvc.put("/api/v1/avatar/outfit") {
            header("Authorization", "Bearer $firstToken")
            contentType = MediaType.APPLICATION_JSON
            content = """{"slot":"TOP","itemCode":null}"""
        }.andExpect { status { isOk() }; jsonPath("$.equipped.TOP") { doesNotExist() } }
        assertTrue(outfits.findById(first.id).orElseThrow().equipped().isEmpty())
        assertTrue(outfits.findById(second.id).isEmpty)
    }

    @Test fun `동시 최초 장착은 복장 행을 한 건만 유지한다`() {
        val user = users.save(UserEntity(displayName = "동시꾸미기${System.nanoTime()}"))
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val results = listOf("STRAW_HAT", "GREEN_BERET").map { code ->
                executor.submit<String> { start.await(); service.equip(user.id, "HAT", code)[com.example.mypasture.user.domain.AvatarSlot.HAT]!! }
            }
            start.countDown()
            assertEquals(setOf("STRAW_HAT", "GREEN_BERET"), results.map { it.get(20, TimeUnit.SECONDS) }.toSet())
            assertEquals(1, outfits.findAll().count { it.userId == user.id })
            assertTrue(outfits.findById(user.id).orElseThrow().hatCode in setOf("STRAW_HAT", "GREEN_BERET"))
        } finally {
            executor.shutdownNow()
        }
    }

    companion object {
        @JvmStatic @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            val url = System.getenv("AVATAR_OUTFIT_TEST_JDBC_URL") ?: return
            registry.add("spring.datasource.url") { url }
            registry.add("spring.datasource.username") { System.getenv("AVATAR_OUTFIT_TEST_JDBC_USER") ?: "postgres" }
            registry.add("spring.datasource.password") { System.getenv("AVATAR_OUTFIT_TEST_JDBC_PASSWORD") ?: "" }
            registry.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
            registry.add("spring.flyway.enabled") { "true" }
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
        }
    }
}
