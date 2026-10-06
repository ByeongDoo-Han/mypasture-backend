package com.example.mypasture.bible.presentation

import com.example.mypasture.auth.application.AuthService
import com.example.mypasture.auth.application.InvalidCredentialsException
import com.example.mypasture.auth.infrastructure.UserRepository
import com.example.mypasture.auth.presentation.dto.DeleteAccountRequest
import com.example.mypasture.bible.application.ScriptureCopyCommand
import com.example.mypasture.bible.application.ScriptureCopyService
import com.example.mypasture.bible.domain.BibleChapterEntity
import com.example.mypasture.bible.domain.BibleChapterContentStatus
import com.example.mypasture.bible.domain.BibleBookEntity
import com.example.mypasture.bible.domain.BibleVerseEntity
import com.example.mypasture.bible.domain.Testament
import com.example.mypasture.bible.infrastructure.BibleChapterEntityRepository
import com.example.mypasture.bible.infrastructure.BibleBookEntityRepository
import com.example.mypasture.bible.infrastructure.BibleVerseEntityRepository
import com.example.mypasture.bible.infrastructure.ScriptureCopyRepository
import com.example.mypasture.user.domain.UserEntity
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.*
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.text.Normalizer
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 필사 HTTP 계약·완료·권한·재시도·경합·탈퇴 파기를 실제 저장소와 검증합니다. */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@TestPropertySource(properties = [
    "app.bible.kor1910-html-path=src/test/resources/bible/kor_html",
    "app.bible.import-enabled=true", "app.firebase.enabled=false",
])
class ScriptureCopyControllerTests(
    @Autowired private val mvc: MockMvc,
    @Autowired private val mapper: ObjectMapper,
    @Autowired private val copies: ScriptureCopyRepository,
    @Autowired private val service: ScriptureCopyService,
    @Autowired private val users: UserRepository,
    @Autowired private val books: BibleBookEntityRepository,
    @Autowired private val verses: BibleVerseEntityRepository,
    @Autowired private val chapters: BibleChapterEntityRepository,
    @Autowired private val auth: AuthService,
    @Autowired private val transactions: PlatformTransactionManager,
    @Autowired private val jdbc: JdbcTemplate,
) {
    @Test
    fun `owner saves canonical snapshot retries resumes completes and deletes`() {
        val token = token()
        val id = UUID.randomUUID()
        val draft = put(token, id, request(typed = "태초에", version = " kor1910 ", book = " gen ")).andExpect {
            status { isOk() }
            jsonPath("$.version") { value("KOR1910") }
            jsonPath("$.bookCode") { value("GEN") }
            jsonPath("$.bookName") { value("창세기") }
            jsonPath("$.revision") { value(1) }
            jsonPath("$.status") { value("DRAFT") }
        }.andReturn()
        val source = mapper.readTree(draft.response.contentAsString)["sourceText"].asText()
        assertTrue(source.isNotBlank())
        val createdAt = mapper.readTree(draft.response.contentAsString)["createdAt"].asText()
        put(token, id, request(typed = "태초에")).andExpect {
            status { isOk() }; jsonPath("$.revision") { value(1) }; jsonPath("$.createdAt") { value(createdAt) }
        }
        mvc.get("/api/v1/scripture-copies/$id") { bearer(token) }.andExpect {
            status { isOk() }; jsonPath("$.typedText") { value("태초에") }
        }
        mvc.get("/api/v1/scripture-copies") { bearer(token) }.andExpect {
            status { isOk() }; jsonPath("$.totalElements") { value(1) }; jsonPath("$.size") { value(20) }
        }
        put(token, id, request(revision = 1, typed = source, reflection = "창조를 기억합니다", complete = true)).andExpect {
            status { isOk() }; jsonPath("$.revision") { value(2) }; jsonPath("$.status") { value("COMPLETED") }
            jsonPath("$.completedAt") { isNotEmpty() }
        }
        put(token, id, request(revision = 1, typed = source, reflection = "창조를 기억합니다", complete = true)).andExpect {
            status { isOk() }; jsonPath("$.revision") { value(2) }
        }
        put(token, id, request(revision = 2, typed = source, complete = true)).andExpect {
            status { isConflict() }; jsonPath("$.code") { value("SCRIPTURE_COPY_CONFLICT") }
        }
        mvc.delete("/api/v1/scripture-copies/$id?expectedRevision=1") { bearer(token) }.andExpect { status { isConflict() } }
        mvc.delete("/api/v1/scripture-copies/$id?expectedRevision=2") { bearer(token) }.andExpect { status { isNoContent() } }
        mvc.delete("/api/v1/scripture-copies/$id?expectedRevision=2") { bearer(token) }.andExpect { status { isNoContent() } }
        assertFalse(copies.existsById(id))
    }

    @Test
    fun `foreign record is hidden on every operation and missing detail is not found`() {
        val owner = token()
        val other = token()
        val id = UUID.randomUUID()
        put(owner, id, request()).andExpect { status { isOk() } }
        mvc.get("/api/v1/scripture-copies/$id") { bearer(other) }.andExpect {
            status { isNotFound() }; jsonPath("$.code") { value("SCRIPTURE_COPY_NOT_FOUND") }
        }
        put(other, id, request()).andExpect {
            status { isNotFound() }; jsonPath("$.code") { value("SCRIPTURE_COPY_NOT_FOUND") }
        }
        mvc.delete("/api/v1/scripture-copies/$id?expectedRevision=1") { bearer(other) }.andExpect {
            status { isNotFound() }; jsonPath("$.code") { value("SCRIPTURE_COPY_NOT_FOUND") }
        }
        mvc.get("/api/v1/scripture-copies") { bearer(other) }.andExpect { jsonPath("$.totalElements") { value(0) } }
        mvc.get("/api/v1/scripture-copies/${UUID.randomUUID()}") { bearer(owner) }.andExpect { status { isNotFound() } }
        assertTrue(copies.existsById(id))
    }

    @Test
    fun `authentication invalid input missing verse and revision location conflicts use stable errors`() {
        val id = UUID.randomUUID()
        mvc.get("/api/v1/scripture-copies").andExpect { status { isUnauthorized() } }
        mvc.put("/api/v1/scripture-copies/$id") { contentType = MediaType.APPLICATION_JSON; content = request() }
            .andExpect { status { isUnauthorized() } }
        val token = token()
        listOf(request(typed = "가".repeat(10001)), request(reflection = "가".repeat(1001)), request(revision = -1), request(verse = 0)).forEach { invalid ->
            put(token, id, invalid).andExpect { status { isBadRequest() }; jsonPath("$.code") { value("INVALID_REQUEST") } }
        }
        for (query in listOf("page=-1", "size=0", "size=51")) {
            mvc.get("/api/v1/scripture-copies?$query") { bearer(token) }.andExpect { status { isBadRequest() } }
        }
        put(token, id, request(verse = 999)).andExpect { status { isNotFound() }; jsonPath("$.code") { value("BIBLE_NOT_FOUND") } }
        put(token, id, request(revision = 1)).andExpect { status { isConflict() } }
        put(token, id, request()).andExpect { status { isOk() } }
        put(token, id, request(typed = "새 내용", revision = 0)).andExpect { status { isConflict() } }
        put(token, id, request(verse = 2, revision = 1)).andExpect { status { isConflict() } }
        mvc.delete("/api/v1/scripture-copies/$id?expectedRevision=0") { bearer(token) }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `completion compares NFC letters marks and all numbers but rejects omissions and empty canonical text`() {
        val token = token()
        val source = "하나님 Café 𐐀 Ⅲ ² 1!"
        val version = "TEST${UUID.randomUUID().toString().take(8).uppercase()}"
        val book = books.saveAndFlush(BibleBookEntity(version = version, code = "GEN", name = "검증용", testament = Testament.OLD, displayOrder = 1))
        chapters.saveAndFlush(BibleChapterEntity(version = version, book = book, chapter = 1, contentStatus = BibleChapterContentStatus.AVAILABLE))
        verses.saveAndFlush(BibleVerseEntity(version = version, book = book, chapter = 1, verse = 1, text = source))
        verses.saveAndFlush(BibleVerseEntity(version = version, book = book, chapter = 1, verse = 2, text = "… !"))
        verses.saveAndFlush(BibleVerseEntity(version = version, book = book, chapter = 1, verse = 3, text = "   "))
        put(token, UUID.randomUUID(), request(version = version, verse = 3)).andExpect {
            status { isNotFound() }; jsonPath("$.code") { value("BIBLE_NOT_FOUND") }
        }
        val id = UUID.randomUUID()
        put(token, id, request(version = version, typed = "하나님 Café 𐐀 1", complete = true)).andExpect {
            status { isBadRequest() }; jsonPath("$.code") { value("SCRIPTURE_COPY_INCOMPLETE") }
        }
        assertFalse(copies.existsById(id))
        put(token, id, request(version = version, typed = Normalizer.normalize(source.replace(" ", "\n"), Normalizer.Form.NFD), complete = true)).andExpect {
            status { isOk() }; jsonPath("$.status") { value("COMPLETED") }
        }
        put(token, UUID.randomUUID(), request(version = version, verse = 2, typed = "!!!", complete = true)).andExpect {
            status { isBadRequest() }; jsonPath("$.code") { value("SCRIPTURE_COPY_INCOMPLETE") }
        }
    }

    @Test
    fun `incomplete completion rolls back and snapshot remains after canonical verse changes`() {
        val token = token()
        val version = "SNAP${UUID.randomUUID().toString().take(8).uppercase()}"
        val book = books.saveAndFlush(BibleBookEntity(version = version, code = "GEN", name = "보존 권", testament = Testament.OLD, displayOrder = 1))
        chapters.saveAndFlush(BibleChapterEntity(version = version, book = book, chapter = 1, contentStatus = BibleChapterContentStatus.AVAILABLE))
        val verse = verses.saveAndFlush(BibleVerseEntity(version = version, book = book, chapter = 1, verse = 1, text = "보존 말씀"))
        val id = UUID.randomUUID()
        put(token, id, request(version = version, typed = "보존")).andExpect { status { isOk() } }
        verse.updateText("변경 말씀")
        verses.saveAndFlush(verse)
        put(token, id, request(version = version, typed = "변경 말씀", revision = 1, complete = true)).andExpect { status { isBadRequest() } }
        assertEquals(1L, copies.findById(id).orElseThrow().revision)
        assertEquals("보존", copies.findById(id).orElseThrow().typedText)
        put(token, id, request(version = version, typed = "보존 말씀", revision = 1, complete = true)).andExpect {
            status { isOk() }; jsonPath("$.sourceText") { value("보존 말씀") }
        }
    }

    @Test
    fun `parallel same UUID creation is idempotent and parallel changes have one winner`() {
        val token = token()
        val id = UUID.randomUUID()
        val createResults = parallel(List(4) { { put(token, id, request(typed = "태초")).andReturn().response.status } })
        assertEquals(listOf(200, 200, 200, 200), createResults.sorted())
        assertEquals(1L, copies.findById(id).orElseThrow().revision)
        val edits = parallel(List(4) { index -> { put(token, id, request(typed = "태초${index}", revision = 1)).andReturn().response.status } })
        assertEquals(listOf(200, 409, 409, 409), edits.sorted())
        assertEquals(2L, copies.findById(id).orElseThrow().revision)
    }

    @Test
    fun `different owners racing same UUID never overwrite owner content`() {
        val tokens = listOf(token(), token())
        val id = UUID.randomUUID()
        val outcomes = parallel(tokens.mapIndexed { index, token -> { put(token, id, request(typed = "소유자$index")).andReturn().response.status } })
        assertEquals(1, outcomes.count { it == 200 })
        assertTrue(outcomes.single { it != 200 } in listOf(404, 409))
        val winner = outcomes.indexOf(200)
        mvc.get("/api/v1/scripture-copies/$id") { bearer(tokens[winner]) }.andExpect {
            status { isOk() }; jsonPath("$.typedText") { value("소유자$winner") }
        }
        mvc.get("/api/v1/scripture-copies/$id") { bearer(tokens[1 - winner]) }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `list pages honor most recently updated order and account deletion destroys drafts and completed records`() {
        val token = token()
        val ids = List(3) { UUID.randomUUID() }
        ids.forEach { put(token, it, request()).andExpect { status { isOk() } } }
        put(token, ids[0], request(typed = "태초에", revision = 1)).andExpect { status { isOk() } }
        mvc.get("/api/v1/scripture-copies?page=0&size=1") { bearer(token) }.andExpect {
            jsonPath("$.totalElements") { value(3) }; jsonPath("$.totalPages") { value(3) }; jsonPath("$.items[0].id") { value(ids[0].toString()) }
        }
        val source = copies.findById(ids[1]).orElseThrow().sourceText
        put(token, ids[1], request(typed = source, revision = 1, complete = true)).andExpect { status { isOk() } }
        mvc.delete("/api/v1/auth/me") {
            bearer(token); contentType = MediaType.APPLICATION_JSON
            content = """{"confirmation":"DELETE","currentPassword":"password123!"}"""
        }.andExpect { status { isNoContent() } }
        ids.forEach { assertFalse(copies.existsById(it)) }
        mvc.get("/api/v1/scripture-copies") { bearer(token) }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `postgres waiting save acquires user before record and cannot resurrect account erased records`() {
        assumeTrue(!System.getenv("SCRIPTURE_COPY_TEST_JDBC_URL").isNullOrBlank(), "Requires PostgreSQL lock inspection")
        for (existing in listOf(false, true)) {
            val user = users.saveAndFlush(UserEntity(email = "copy-lock-${UUID.randomUUID()}@example.com", displayName = "잠금 검증"))
            val id = UUID.randomUUID()
            if (existing) service.save(user.id, id, command())
            val executor = Executors.newSingleThreadExecutor()
            val started = CountDownLatch(1)
            val pid = AtomicInteger()
            var result: java.util.concurrent.Future<Throwable?>? = null
            try {
                TransactionTemplate(transactions).executeWithoutResult {
                    users.findActiveByIdForUpdate(user.id)!!
                    result = executor.submit<Throwable?> {
                        runCatching {
                            TransactionTemplate(transactions).executeWithoutResult {
                                pid.set(jdbc.queryForObject("select pg_backend_pid()", Int::class.java)!!)
                                started.countDown()
                                service.save(user.id, id, command().copy(typedText = "새 입력", expectedRevision = if (existing) 1 else 0))
                            }
                        }.exceptionOrNull()
                    }
                    assertTrue(started.await(10, TimeUnit.SECONDS))
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    var blocked = false
                    while (System.nanoTime() < deadline) {
                        blocked = jdbc.queryForObject("select pg_backend_pid() = any(pg_blocking_pids(?))", Boolean::class.java, pid.get())!!
                        if (blocked) break
                        Thread.sleep(25)
                    }
                    assertTrue(blocked, "Save must block on the account row before accessing its record")
                    if (existing) jdbc.queryForObject("select id from scripture_copies where id = ? for update nowait", UUID::class.java, id)
                    auth.deleteAccount(user.id, Instant.now(), DeleteAccountRequest("DELETE", null))
                }
                val failure = result!!.get(20, TimeUnit.SECONDS)
                assertTrue(generateSequence(failure) { it.cause }.any { it is InvalidCredentialsException })
                assertFalse(copies.existsById(id))
            } finally { executor.shutdownNow() }
        }
    }

    private fun command() = ScriptureCopyCommand("KOR1910", "GEN", 1, 1, "", "", 0, false)
    private fun request(
        typed: String = "", reflection: String = "", revision: Long = 0, complete: Boolean = false,
        version: String = "KOR1910", book: String = "GEN", verse: Int = 1,
    ) = mapper.writeValueAsString(mapOf("version" to version, "bookCode" to book, "chapter" to 1, "verse" to verse,
        "typedText" to typed, "reflection" to reflection, "expectedRevision" to revision, "complete" to complete))

    private fun put(token: String, id: UUID, body: String) = mvc.put("/api/v1/scripture-copies/$id") {
        bearer(token); contentType = MediaType.APPLICATION_JSON; content = body
    }
    private fun MockHttpServletRequestDsl.bearer(token: String) = header("Authorization", "Bearer $token")

    private fun <T> parallel(work: List<() -> T>): List<T> {
        val executor = Executors.newFixedThreadPool(work.size)
        val start = CountDownLatch(1)
        try {
            val futures = work.map { task -> executor.submit<T> { start.await(); task() } }
            start.countDown()
            return futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally { executor.shutdownNow() }
    }

    private fun token(): String {
        val email = "copy-${UUID.randomUUID()}@example.com"
        mvc.post("/api/v1/auth/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"password123!","displayName":"필사 독자"}"""
        }.andExpect { status { isCreated() } }
        val result = mvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"password123!"}"""
        }.andExpect { status { isOk() } }.andReturn()
        return mapper.readTree(result.response.contentAsString)["token"].asText()
    }

    companion object {
        @JvmStatic @DynamicPropertySource
        fun postgres(registry: DynamicPropertyRegistry) {
            val url = System.getenv("SCRIPTURE_COPY_TEST_JDBC_URL")?.takeIf { it.isNotBlank() } ?: return
            registry.add("spring.datasource.url") { url }
            registry.add("spring.datasource.username") { System.getenv("SCRIPTURE_COPY_TEST_JDBC_USER") ?: "postgres" }
            registry.add("spring.datasource.password") { System.getenv("SCRIPTURE_COPY_TEST_JDBC_PASSWORD") ?: "" }
            registry.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
            registry.add("spring.flyway.enabled") { "true" }
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
        }
    }
}
