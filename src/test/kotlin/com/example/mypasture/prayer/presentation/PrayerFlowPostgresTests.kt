package com.example.mypasture.prayer.presentation

import com.example.mypasture.auth.domain.uniqueTestNickname
import com.example.mypasture.auth.infrastructure.JwtTokenProvider
import com.example.mypasture.auth.infrastructure.UserRepository
import com.example.mypasture.prayer.application.PrayerService
import com.example.mypasture.prayer.infrastructure.PrayerAdIntentRepository
import com.example.mypasture.user.domain.UserEntity
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** 기도 공개 범위와 발송 원장을 H2 및 실제 PostgreSQL/Flyway에서 검증합니다. */
@SpringBootTest(properties = ["app.bible.import-enabled=false", "app.firebase.enabled=false", "app.prayer.ads.enabled=true",
    "app.prayer.ads.android-unit-id=ca-app-pub-1234567890123456/1234567890", "app.prayer.ads.ios-unit-id=ca-app-pub-1234567890123456/1234567891"])
@AutoConfigureMockMvc
class PrayerFlowPostgresTests(
    @Autowired private val mvc: MockMvc,
    @Autowired private val mapper: ObjectMapper,
    @Autowired private val users: UserRepository,
    @Autowired private val jwt: JwtTokenProvider,
    @Autowired private val service: PrayerService,
    @Autowired private val intents: PrayerAdIntentRepository,
    @Autowired private val jdbc: JdbcTemplate,
) {
    @Test
    fun `비공개 기도제목은 외부인이 읽거나 편지를 보낼 수 없고 친구 편지 한 건만 받는다`() {
        if (System.getenv("PRAYER_TEST_JDBC_URL") != null)
            assertEquals("PostgreSQL", jdbc.dataSource!!.connection.use { it.metaData.databaseProductName })
        val owner = user()
        val friend = user()
        val stranger = user()
        val ownerToken = token(owner)
        val friendToken = token(friend)
        val strangerToken = token(stranger)

        mvc.get("/api/v1/prayer/topics/me").andExpect { status { isUnauthorized() } }
        val privateTopic = mvc.put("/api/v1/prayer/topics/me") {
            header("Authorization", "Bearer $ownerToken"); contentType = MediaType.APPLICATION_JSON
            content = """{"content":"이번 주 기도제목", "visibleToFriends":false}"""
        }.andExpect { status { isOk() } }.andReturn()
        val topicId = mapper.readTree(privateTopic.response.contentAsString)["id"].asText()
        mvc.get("/api/v1/prayer/topics/friends/${owner.id}") { header("Authorization", "Bearer $strangerToken") }
            .andExpect { status { isNotFound() } }

        val request = mvc.post("/api/v1/prayer/friends/requests") {
            header("Authorization", "Bearer $friendToken"); contentType = MediaType.APPLICATION_JSON
            content = """{"userId":"${owner.id}"}"""
        }.andExpect { status { isCreated() } }.andReturn()
        val friendshipId = mapper.readTree(request.response.contentAsString)["id"].asText()
        mvc.post("/api/v1/prayer/friends/$friendshipId/accept") { header("Authorization", "Bearer $strangerToken") }
            .andExpect { status { isNotFound() } }
        mvc.post("/api/v1/prayer/friends/$friendshipId/accept") { header("Authorization", "Bearer $ownerToken") }
            .andExpect { status { isOk() } }
        mvc.get("/api/v1/prayer/topics/friends/${owner.id}") { header("Authorization", "Bearer $friendToken") }
            .andExpect { status { isNotFound() } }
        mvc.post("/api/v1/prayer/topics/$topicId/letters") {
            header("Authorization", "Bearer $friendToken"); contentType = MediaType.APPLICATION_JSON
            content = """{"content":"함께 기도합니다"}"""
        }.andExpect { status { isNotFound() } }

        mvc.put("/api/v1/prayer/topics/me") {
            header("Authorization", "Bearer $ownerToken"); contentType = MediaType.APPLICATION_JSON
            content = """{"content":"함께 기도할 제목", "visibleToFriends":true}"""
        }.andExpect { status { isOk() } }
        mvc.get("/api/v1/prayer/topics/feed") { header("Authorization", "Bearer $strangerToken") }
            .andExpect { status { isOk() }; jsonPath("$.length()") { value(0) } }
        mvc.get("/api/v1/prayer/topics/feed") { header("Authorization", "Bearer $friendToken") }
            .andExpect { status { isOk() }; jsonPath("$[0].content") { value("함께 기도할 제목") } }
        mvc.post("/api/v1/prayer/topics/$topicId/letters") {
            header("Authorization", "Bearer $friendToken"); contentType = MediaType.APPLICATION_JSON
            content = """{"content":"기도 편지입니다"}"""
        }.andExpect { status { isCreated() } }
        mvc.post("/api/v1/prayer/topics/$topicId/letters") {
            header("Authorization", "Bearer $friendToken"); contentType = MediaType.APPLICATION_JSON
            content = """{"content":"중복 편지입니다"}"""
        }.andExpect { status { isConflict() } }
        mvc.get("/api/v1/prayer/letters?box=RECEIVED") { header("Authorization", "Bearer $ownerToken") }
            .andExpect { status { isOk() }; jsonPath("$[0].content") { value("기도 편지입니다") } }
        mvc.get("/api/v1/prayer/letters?box=RECEIVED") { header("Authorization", "Bearer $strangerToken") }
            .andExpect { status { isOk() }; jsonPath("$.length()") { value(0) } }
        mvc.post("/api/v1/prayer/friends/users/${owner.id}/block") { header("Authorization", "Bearer $friendToken") }
            .andExpect { status { isNoContent() } }
        mvc.get("/api/v1/prayer/topics/feed") { header("Authorization", "Bearer $friendToken") }
            .andExpect { status { isOk() }; jsonPath("$.length()") { value(0) } }
        mvc.delete("/api/v1/prayer/friends/users/${friend.id}/block") { header("Authorization", "Bearer $ownerToken") }
            .andExpect { status { isNotFound() } }
        mvc.delete("/api/v1/prayer/friends/users/${owner.id}/block") { header("Authorization", "Bearer $friendToken") }
            .andExpect { status { isNoContent() } }
    }

    @Test
    fun `동시 편지는 수신 대상 한 칸만 소비하고 검증한 광고 보상은 멱등하다`() {
        val sender = user()
        val recipient = user()
        val relation = service.requestFriend(sender.id, recipient.id)
        service.acceptFriend(recipient.id, relation.id)
        val topic = service.saveTopic(recipient.id, "이번 주 기도", true)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val calls = (1..2).map { pool.submit(Callable { runCatching { service.sendLetter(sender.id, topic.id, "기도합니다") } }) }
            assertEquals(1, calls.count { it.get().isSuccess })
            assertEquals(1, service.quota(sender.id).used)
        } finally { pool.shutdownNow() }

        val additionalTopics = (1..3).map {
            val other = user()
            val added = service.requestFriend(sender.id, other.id)
            service.acceptFriend(other.id, added.id)
            service.saveTopic(other.id, "추가 기도제목 $it", true)
        }
        additionalTopics.take(2).forEach { service.sendLetter(sender.id, it.id, "기도합니다") }
        assertEquals(0, service.quota(sender.id).remaining)
        assertThrows(RuntimeException::class.java) { service.sendLetter(sender.id, additionalTopics.last().id, "한도 초과") }

        val intent = service.createAdIntent(sender.id, "ANDROID")
        service.claimAdReward(intent.id, "txn-${UUID.randomUUID()}", intent.adUnitId, Instant.now())
        val transaction = intents.findById(intent.id).orElseThrow().transactionId!!
        service.claimAdReward(intent.id, transaction, intent.adUnitId, Instant.now())
        assertEquals(1, service.quota(sender.id).bonus)
        service.sendLetter(sender.id, additionalTopics.last().id, "보상 확인 후 기도합니다")
        assertEquals(4, service.quota(sender.id).used)
        assertThrows(RuntimeException::class.java) {
            service.claimAdReward(intent.id, "different-${UUID.randomUUID()}", intent.adUnitId, Instant.now())
        }
    }

    private fun user() = users.saveAndFlush(UserEntity(email = "prayer-${UUID.randomUUID()}@example.com", displayName = uniqueTestNickname()))
    private fun token(user: UserEntity) = jwt.issueAccessToken(user).accessToken

    companion object {
        private val schema = "prayer_${UUID.randomUUID().toString().replace("-", "")}"
        @JvmStatic @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            val url = System.getenv("PRAYER_TEST_JDBC_URL") ?: return
            registry.add("spring.datasource.url") { "$url${if ('?' in url) '&' else '?'}currentSchema=$schema,public" }
            registry.add("spring.datasource.username") { System.getenv("PRAYER_TEST_JDBC_USER") ?: "postgres" }
            registry.add("spring.datasource.password") { System.getenv("PRAYER_TEST_JDBC_PASSWORD") ?: "" }
            registry.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
            registry.add("spring.flyway.enabled") { "true" }
            registry.add("spring.flyway.schemas") { schema }
            registry.add("spring.flyway.default-schema") { schema }
            registry.add("spring.jpa.properties.hibernate.default_schema") { schema }
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
        }
    }
}
