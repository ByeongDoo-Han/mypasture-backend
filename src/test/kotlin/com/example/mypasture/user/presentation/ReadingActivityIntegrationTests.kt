package com.example.mypasture.user.presentation

import com.example.mypasture.auth.infrastructure.JwtTokenProvider
import com.example.mypasture.auth.infrastructure.UserRepository
import com.example.mypasture.user.domain.UserEntity
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 실제 읽기 API의 저장 결과가 활동 API에 계정별로 정확히 반영되는지 검증합니다. */
@SpringBootTest(properties = ["app.bible.import-enabled=true"])
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(ReadingActivityIntegrationTests.ActivityClockConfiguration::class)
class ReadingActivityIntegrationTests(
    @Autowired private val mvc: MockMvc,
    @Autowired private val users: UserRepository,
    @Autowired private val jwt: JwtTokenProvider,
) {
    @Test
    fun `concurrent first reads and completion updates remain one completed chapter`() {
        val token = tokenFor("Concurrent reader")
        concurrentReads(token, includeCompletion = false)
        expectToday(token, chaptersRead = 1, chaptersCompleted = 0)

        // 기존 행에 다른 기기의 오래된 미완독 요청이 섞여도 완독 상태를 유지합니다.
        concurrentReads(token, includeCompletion = true)
        expectToday(token, chaptersRead = 1, chaptersCompleted = 1)
        mvc.get("/api/v1/reading/history?from=2026-10-01&to=2026-10-01") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].lastVerse") { value(2) }
            jsonPath("$[0].completed") { value(true) }
        }
        mvc.get("/api/v1/reading/progress") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].lastVerse") { value(2) }
            jsonPath("$[0].completed") { value(true) }
        }
    }

    @Test
    fun `reading retries count one chapter and preserve completion without inventing a visit`() {
        val token = tokenFor("Reading activity")
        repeat(2) { record(token, lastVerse = 1) }
        expectToday(token, chaptersRead = 1, chaptersCompleted = 0)

        // GEN 1 테스트 본문의 마지막 절은 2절이며 API가 완독 여부를 정규화합니다.
        record(token, lastVerse = 2)
        record(token, lastVerse = 1)
        expectToday(token, chaptersRead = 1, chaptersCompleted = 1)
        mvc.get("/api/v1/reading/history?from=2026-10-01&to=2026-10-01") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].lastVerse") { value(2) }
            jsonPath("$[0].completed") { value(true) }
        }

        repeat(2) {
            mvc.put("/api/v1/activity/today") { header("Authorization", "Bearer $token") }
                .andExpect { status { isOk() }; jsonPath("$.date") { value("2026-10-01") } }
        }
        expectToday(token, chaptersRead = 1, chaptersCompleted = 1, visited = true)
    }

    @Test
    fun `reading and calendar ownership follow JWT across separate client sessions`() {
        val owner = users.save(UserEntity(displayName = "First reader"))
        val ownerToken = jwt.issueAccessToken(owner).accessToken
        val other = users.save(UserEntity(displayName = "Second reader"))
        val otherToken = jwt.issueAccessToken(other).accessToken
        record(ownerToken, lastVerse = 1)
        mvc.put("/api/v1/reading/progress?userId=${owner.id}") {
            header("Authorization", "Bearer $otherToken")
            contentType = MediaType.APPLICATION_JSON
            content = """{"version":"KOR1910","bookCode":"GEN","chapter":2,"lastVerse":1,"completed":true,"timezone":"Asia/Seoul"}"""
        }.andExpect { status { isOk() } }

        // 같은 계정의 새 토큰으로 다른 앱·웹 세션에서도 같은 집계를 조회합니다.
        expectToday(jwt.issueAccessToken(owner).accessToken, chaptersRead = 1, chaptersCompleted = 0)
        expectToday(otherToken, chaptersRead = 1, chaptersCompleted = 1)
        mvc.get("/api/v1/activity?year=2026&userId=${other.id}") {
            header("Authorization", "Bearer $ownerToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$.days[-1].chaptersCompleted") { value(0) }
        }
    }

    @Test
    fun `invalid and anonymous reading requests do not create activity`() {
        val token = tokenFor("Rejected reading")
        val body = """{"version":"KOR1910","bookCode":"GEN","chapter":1,"lastVerse":199,"timezone":"Asia/Seoul"}"""
        mvc.put("/api/v1/reading/progress") {
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andExpect { status { isUnauthorized() } }
        mvc.put("/api/v1/reading/progress") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andExpect { status { isBadRequest() }; jsonPath("$.code") { value("INVALID_REQUEST") } }
        expectToday(token, chaptersRead = 0, chaptersCompleted = 0)
    }

    @Test
    fun `invalid timezone rolls back changes to existing reading state and history`() {
        val token = tokenFor("Timezone reader")
        record(token, lastVerse = 1)
        mvc.put("/api/v1/reading/progress") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"version":"KOR1910","bookCode":"GEN","chapter":1,"lastVerse":2,"timezone":"Invalid/Timezone"}"""
        }.andExpect { status { isBadRequest() }; jsonPath("$.code") { value("INVALID_REQUEST") } }
        expectToday(token, chaptersRead = 1, chaptersCompleted = 0)
        mvc.get("/api/v1/reading/progress") { header("Authorization", "Bearer $token") }
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(1) }
                jsonPath("$[0].lastVerse") { value(1) }
                jsonPath("$[0].completed") { value(false) }
            }
        mvc.get("/api/v1/reading/history?from=2026-10-01&to=2026-10-01") { header("Authorization", "Bearer $token") }
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(1) }
                jsonPath("$[0].lastVerse") { value(1) }
                jsonPath("$[0].completed") { value(false) }
            }
    }

    private fun tokenFor(name: String) = jwt.issueAccessToken(users.save(UserEntity(displayName = name))).accessToken

    private fun concurrentReads(token: String, includeCompletion: Boolean) {
        val pool = Executors.newFixedThreadPool(6)
        val ready = CountDownLatch(6)
        val start = CountDownLatch(1)
        try {
            val requests = (0 until 6).map { index ->
                pool.submit {
                    ready.countDown()
                    check(start.await(20, TimeUnit.SECONDS)) { "Concurrent reads did not start" }
                    record(token, lastVerse = if (includeCompletion && index == 0) 2 else 1)
                }
            }
            check(ready.await(20, TimeUnit.SECONDS)) { "Concurrent readers were not ready" }
            start.countDown()
            requests.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            pool.shutdownNow()
        }
    }

    private fun record(token: String, lastVerse: Int) {
        mvc.put("/api/v1/reading/progress") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"version":"KOR1910","bookCode":"GEN","chapter":1,"lastVerse":$lastVerse,"completed":false,"timezone":"Asia/Seoul"}"""
        }.andExpect { status { isOk() } }
    }

    private fun expectToday(token: String, chaptersRead: Int, chaptersCompleted: Int, visited: Boolean = false) {
        mvc.get("/api/v1/activity?year=2026") { header("Authorization", "Bearer $token") }
            .andExpect {
                status { isOk() }
                jsonPath("$.today") { value("2026-10-01") }
                jsonPath("$.timezone") { value("Asia/Seoul") }
                jsonPath("$.days[-1].date") { value("2026-10-01") }
                jsonPath("$.days[-1].chaptersRead") { value(chaptersRead) }
                jsonPath("$.days[-1].chaptersCompleted") { value(chaptersCompleted) }
                jsonPath("$.days[-1].visited") { value(visited) }
            }
    }

    /** UTC 날짜와 다른 한국 날짜에서도 읽기와 방문이 같은 날에 집계되도록 시간을 고정합니다. */
    @TestConfiguration
    class ActivityClockConfiguration {
        @Bean
        fun activityIntegrationClock(): Clock = Clock.fixed(Instant.parse("2026-09-30T15:30:00Z"), ZoneOffset.UTC)
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) = ActivityCalendarControllerTests.database(registry)
    }
}
