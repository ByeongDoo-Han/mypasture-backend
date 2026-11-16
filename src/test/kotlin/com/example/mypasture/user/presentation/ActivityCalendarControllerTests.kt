package com.example.mypasture.user.presentation

import com.example.mypasture.auth.infrastructure.JwtTokenProvider
import com.example.mypasture.auth.infrastructure.UserRepository
import com.example.mypasture.reading.domain.BibleReadingHistoryEntity
import com.example.mypasture.reading.infrastructure.BibleReadingHistoryRepository
import com.example.mypasture.user.application.ActivityCalendarService
import com.example.mypasture.user.domain.UserEntity
import com.example.mypasture.user.infrastructure.UserVisitRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import java.time.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 실제 저장소로 인증 격리, 동시 방문, 읽기 중복 및 날짜 경계를 검증합니다. */
@SpringBootTest
@AutoConfigureMockMvc
class ActivityCalendarControllerTests(
    @Autowired private val mvc: MockMvc,
    @Autowired private val users: UserRepository,
    @Autowired private val jwt: JwtTokenProvider,
    @Autowired private val service: ActivityCalendarService,
    @Autowired private val visits: UserVisitRepository,
    @Autowired private val reading: BibleReadingHistoryRepository,
) {
    @Test fun `익명 요청은 활동을 읽거나 기록할 수 없다`() {
        mvc.get("/api/v1/activity").andExpect { status { isUnauthorized() } }
        mvc.put("/api/v1/activity/today").andExpect { status { isUnauthorized() } }
    }

    @Test fun `동시 기기의 방문 기록은 멱등하고 소유권은 JWT를 따른다`() {
        val user = users.save(UserEntity(displayName = "Activity test"))
        val pool = Executors.newFixedThreadPool(6)
        val start = CountDownLatch(1)
        try {
            val requests = (1..6).map { pool.submit<LocalDate> { start.await(); service.visit(user.id) } }
            start.countDown()
            val dates = requests.map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(1, dates.toSet().size)
            assertEquals(1, visits.findAllByUserIdAndVisitedOnBetween(user.id, dates.first(), dates.first()).size)
        } finally { pool.shutdownNow() }
        val other = users.save(UserEntity(displayName = "Other account"))
        assertTrue(service.calendar(other.id, null).days.none { it.visited })
        val token = jwt.issueAccessToken(other).accessToken
        mvc.put("/api/v1/activity/today?userId=${user.id}") { header("Authorization", "Bearer $token") }
            .andExpect { status { isOk() }; jsonPath("$.date") { exists() } }
        assertEquals(1, service.calendar(other.id, null).days.count { it.visited })
        mvc.get("/api/v1/activity?year=1999") { header("Authorization", "Bearer $token") }.andExpect { status { isBadRequest() } }
        mvc.get("/api/v1/activity?year=99999") { header("Authorization", "Bearer $token") }.andExpect { status { isBadRequest() } }
        mvc.get("/api/v1/activity") { header("Authorization", "Bearer $token") }
            .andExpect { status { isOk() }; jsonPath("$.timezone") { value("Asia/Seoul") } }
    }

    @Test fun `달력은 윤일을 포함하고 번역본별 장 중복을 제거하며 방문을 지어내지 않는다`() {
        val user = users.save(UserEntity(displayName = "Reader"))
        val other = users.save(UserEntity(displayName = "Other reader"))
        val day = LocalDate.of(2024, 2, 29)
        fun row(owner: UserEntity, version: String, chapter: Int, complete: Boolean) = BibleReadingHistoryEntity(
            userId = owner.id, bibleVersion = version, bookCode = "GEN", chapter = chapter,
            readOn = day, lastVerse = 1, completed = complete,
        )
        reading.saveAll(listOf(row(user, "KOR1910", 1, false), row(user, "KJV", 1, true), row(user, "KOR1910", 2, false), row(other, "KOR1910", 3, true)))
        val calendar = service.calendar(user.id, 2024)
        assertEquals(366, calendar.days.size)
        val leap = calendar.days.single { it.date == day }
        assertEquals(2, leap.chaptersRead)
        assertEquals(1, leap.chaptersCompleted)
        assertFalse(leap.visited)
        assertTrue(calendar.days.filter { it.date != day }.all { it.chaptersRead == 0 })
    }

    @Test fun `달력은 한국 자정을 기준으로 하고 미래 날짜를 제외한다`() {
        val user = users.save(UserEntity(displayName = "Boundary"))
        val before = ActivityCalendarService(users, visits, reading, Clock.fixed(Instant.parse("2025-12-31T14:59:59Z"), ZoneOffset.UTC))
        val after = ActivityCalendarService(users, visits, reading, Clock.fixed(Instant.parse("2025-12-31T15:00:00Z"), ZoneOffset.UTC))
        assertEquals(LocalDate.of(2025, 12, 31), before.calendar(user.id, null).today)
        val newYear = after.calendar(user.id, null)
        assertEquals(2026, newYear.year)
        assertEquals(listOf(LocalDate.of(2026, 1, 1)), newYear.days.map { it.date })
    }

    companion object {
        @JvmStatic @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            val url = System.getenv("ACTIVITY_TEST_JDBC_URL") ?: return
            registry.add("spring.datasource.url") { url }
            registry.add("spring.datasource.username") { System.getenv("ACTIVITY_TEST_JDBC_USER") ?: "postgres" }
            registry.add("spring.datasource.password") { System.getenv("ACTIVITY_TEST_JDBC_PASSWORD") ?: "" }
            registry.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
            registry.add("spring.flyway.enabled") { "true" }
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
        }
    }
}
