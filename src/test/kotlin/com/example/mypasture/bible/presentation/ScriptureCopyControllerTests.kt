package com.example.mypasture.bible.presentation

import com.example.mypasture.auth.domain.uniqueTestNickname

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
    fun `postgres waiting copy mutations acquire user before record and cannot resurrect erased records`() {
        assumeTrue(!System.getenv("SCRIPTURE_COPY_TEST_JDBC_URL").isNullOrBlank(), "Requires PostgreSQL lock inspection")
        for (operation in listOf("create", "update", "reflection", "delete")) {
            val existing = operation != "create"
            val user = users.saveAndFlush(UserEntity(email = "copy-lock-${UUID.randomUUID()}@example.com", displayName = "잠금 검증"))
            val id = UUID.randomUUID()
            if (existing) {
                val source = if (operation == "reflection") {
                    val book = books.findByVersionAndCode("KOR1910", "GEN")!!
                    verses.findByVersionAndBook_IdAndChapterAndVerse("KOR1910", book.id, 1, 1)!!.text
                } else ""
                service.save(user.id, id, command().copy(typedText = source, complete = operation == "reflection"))
            }
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
                                when (operation) {
                                    "reflection" -> service.updateReflection(user.id, id, "탈퇴 중 묵상", 1)
                                    "delete" -> service.delete(user.id, id, 1)
                                    else -> service.save(user.id, id, command().copy(typedText = "새 입력", expectedRevision = if (existing) 1 else 0))
                                }
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
                    assertTrue(blocked, "Copy mutation must block on the account row before accessing its record")
                    if (existing) jdbc.queryForObject("select id from scripture_copies where id = ? for update nowait", UUID::class.java, id)
                    auth.deleteAccount(user.id, Instant.now(), DeleteAccountRequest("DELETE", null))
                }
                val failure = result!!.get(20, TimeUnit.SECONDS)
                assertTrue(generateSequence(failure) { it.cause }.any { it is InvalidCredentialsException })
                assertFalse(copies.existsById(id))
            } finally { executor.shutdownNow() }
        }
    }

    @Test
    fun `range snapshot joins canonical verses and legacy omitted end resumes without truncating`() {
        val token = token()
        val (version, selected) = passageFixture(listOf(1 to "첫 말씀", 2 to "둘째 말씀", 3 to "셋째 말씀"))
        val source = selected.joinToString("\n") { it.text }
        val id = UUID.randomUUID()
        put(token, id, request(version = version, endVerse = 3)).andExpect {
            status { isOk() }; jsonPath("$.endVerse") { value(3) }; jsonPath("$.sourceText") { value(source) }
            jsonPath("$.revision") { value(1) }
        }
        mvc.get("/api/v1/scripture-copies/$id") { bearer(token) }.andExpect {
            status { isOk() }; jsonPath("$.endVerse") { value(3) }
        }
        mvc.get("/api/v1/scripture-copies") { bearer(token) }.andExpect {
            jsonPath("$.items[0].endVerse") { value(3) }
        }
        selected[1].updateText("뒤늦게 변경된 말씀")
        verses.saveAndFlush(selected[1])
        put(token, id, request(version = version, typed = "첫 말씀", revision = 1)).andExpect {
            status { isOk() }; jsonPath("$.endVerse") { value(3) }; jsonPath("$.sourceText") { value(source) }
            jsonPath("$.revision") { value(2) }
        }
        put(token, id, request(version = version, typed = "첫 말씀", revision = 2, complete = true)).andExpect {
            status { isBadRequest() }; jsonPath("$.code") { value("SCRIPTURE_COPY_INCOMPLETE") }
        }
        assertEquals(2L, copies.findById(id).orElseThrow().revision)
        put(token, id, request(version = version, typed = source.replace("\n", " "), revision = 2, complete = true)).andExpect {
            status { isOk() }; jsonPath("$.status") { value("COMPLETED") }; jsonPath("$.revision") { value(3) }
            jsonPath("$.sourceText") { value(source) }; jsonPath("$.endVerse") { value(3) }
        }
        put(token, id, request(version = version, typed = source.replace("\n", " "), revision = 2, complete = true, endVerse = 3)).andExpect {
            status { isOk() }; jsonPath("$.revision") { value(3) }
        }
        put(token, id, request(version = version, typed = source, revision = 3, complete = true, endVerse = 2)).andExpect {
            status { isConflict() }; jsonPath("$.code") { value("SCRIPTURE_COPY_CONFLICT") }
        }
        val singleId = UUID.randomUUID()
        put(token, singleId, request()).andExpect { status { isOk() }; jsonPath("$.endVerse") { value(1) } }
        put(token, singleId, request(revision = 1, endVerse = 2)).andExpect { status { isConflict() } }
    }

    @Test
    fun `range allows twenty verses and rejects invalid bounds missing middle and blank middle atomically`() {
        val token = token()
        val (version) = passageFixture((1..21).map { it to "말씀 $it" })
        put(token, UUID.randomUUID(), request(version = version, endVerse = 20)).andExpect {
            status { isOk() }; jsonPath("$.endVerse") { value(20) }
        }
        for (body in listOf(request(version = version, endVerse = 21), request(version = version, endVerse = 0), request(version = version, verse = 2, endVerse = 1))) {
            val id = UUID.randomUUID()
            put(token, id, body).andExpect { status { isBadRequest() }; jsonPath("$.code") { value("INVALID_REQUEST") } }
            assertFalse(copies.existsById(id))
        }
        for (texts in listOf(listOf(1 to "첫 말씀", 3 to "마지막 말씀"), listOf(1 to "첫 말씀", 2 to "   ", 3 to "마지막 말씀"))) {
            val (missingVersion) = passageFixture(texts)
            val id = UUID.randomUUID()
            put(token, id, request(version = missingVersion, endVerse = 3)).andExpect {
                status { isNotFound() }; jsonPath("$.code") { value("BIBLE_NOT_FOUND") }
            }
            assertFalse(copies.existsById(id))
        }
    }

    @Test
    fun `joined snapshot enforces ten thousand UTF16 characters including separators`() {
        val token = token()
        val (version, selected) = passageFixture(listOf(1 to "𐐀".repeat(2500), 2 to "가".repeat(4999)))
        put(token, UUID.randomUUID(), request(version = version, endVerse = 2)).andExpect { status { isOk() } }
        selected[1].updateText("가".repeat(5000))
        verses.saveAndFlush(selected[1])
        val id = UUID.randomUUID()
        put(token, id, request(version = version, endVerse = 2)).andExpect {
            status { isBadRequest() }; jsonPath("$.code") { value("INVALID_REQUEST") }
        }
        assertFalse(copies.existsById(id))
    }

    @Test
    fun `completed reflection changes independently with retries while original completion and legacy PUT stay immutable`() {
        val token = token()
        val id = completedCopy(token)
        val before = copies.findById(id).orElseThrow()
        patch(token, id, "오늘 마음에 남은 말씀", before.revision).andExpect {
            status { isOk() }; jsonPath("$.reflection") { value("오늘 마음에 남은 말씀") }
            jsonPath("$.revision") { value(3) }; jsonPath("$.status") { value("COMPLETED") }
        }
        val after = copies.findById(id).orElseThrow()
        assertEquals(before.sourceText, after.sourceText)
        assertEquals(before.typedText, after.typedText)
        assertEquals(before.completedAt, after.completedAt)
        assertEquals(before.createdAt, after.createdAt)
        assertEquals(before.endVerse, after.endVerse)
        patch(token, id, "오늘 마음에 남은 말씀", before.revision).andExpect {
            status { isOk() }; jsonPath("$.revision") { value(3) }
        }
        patch(token, id, "오래된 다른 묵상", before.revision).andExpect {
            status { isConflict() }; jsonPath("$.code") { value("SCRIPTURE_COPY_CONFLICT") }
        }
        put(token, id, request(typed = after.typedText, reflection = "PUT으로 바꾸기", revision = after.revision, complete = true)).andExpect {
            status { isConflict() }
        }
        patch(token, id, "", after.revision).andExpect { status { isOk() }; jsonPath("$.revision") { value(4) } }
        mvc.delete("/api/v1/scripture-copies/$id?expectedRevision=3") { bearer(token) }.andExpect { status { isConflict() } }
        mvc.delete("/api/v1/scripture-copies/$id?expectedRevision=4") { bearer(token) }.andExpect { status { isNoContent() } }
        patch(token, id, "삭제 후 복원 시도", 4).andExpect {
            status { isNotFound() }; jsonPath("$.code") { value("SCRIPTURE_COPY_NOT_FOUND") }
        }
        assertFalse(copies.existsById(id))
    }

    @Test
    fun `reflection PATCH requires authentication ownership completed state and valid revision and length`() {
        val token = token()
        val id = completedCopy(token)
        mvc.patch("/api/v1/scripture-copies/$id/reflection") {
            contentType = MediaType.APPLICATION_JSON; content = """{"reflection":"묵상","expectedRevision":2}"""
        }.andExpect { status { isUnauthorized() } }
        patch(token(), id, "다른 사용자의 묵상", 2).andExpect {
            status { isNotFound() }; jsonPath("$.code") { value("SCRIPTURE_COPY_NOT_FOUND") }
        }
        patch(token, UUID.randomUUID(), "없는 기록", 1).andExpect { status { isNotFound() } }
        patch(token, id, "가".repeat(1001), 2).andExpect { status { isBadRequest() }; jsonPath("$.code") { value("INVALID_REQUEST") } }
        patch(token, id, "묵상", 0).andExpect { status { isBadRequest() }; jsonPath("$.code") { value("INVALID_REQUEST") } }
        val draft = UUID.randomUUID()
        put(token, draft, request()).andExpect { status { isOk() } }
        patch(token, draft, "", 1).andExpect { status { isConflict() } }
        patch(token, draft, "아직 완료 전", 1).andExpect { status { isConflict() } }
        assertEquals(1L, copies.findById(draft).orElseThrow().revision)
    }

    @Test
    fun `parallel reflection changes have one winner and identical stale retries keep its revision`() {
        val token = token()
        val id = completedCopy(token)
        val outcomes = parallel(List(4) { index -> { patch(token, id, "묵상 $index", 2).andReturn().response.status } })
        assertEquals(listOf(200, 409, 409, 409), outcomes.sorted())
        val winner = copies.findById(id).orElseThrow()
        assertEquals(3L, winner.revision)
        val retries = parallel(List(4) { { patch(token, id, winner.reflection, 2).andReturn().response.status } })
        assertEquals(listOf(200, 200, 200, 200), retries)
        assertEquals(3L, copies.findById(id).orElseThrow().revision)
    }

    @Test
    fun `reflection and deletion race cannot recreate record or silently discard a newer reflection`() {
        val token = token()
        val id = completedCopy(token)
        val outcomes = parallel(listOf(
            { patch(token, id, "삭제 경합 묵상", 2).andReturn().response.status },
            { mvc.delete("/api/v1/scripture-copies/$id?expectedRevision=2") { bearer(token) }.andReturn().response.status },
        ))
        when (outcomes) {
            listOf(200, 409) -> {
                val record = copies.findById(id).orElseThrow()
                assertEquals("삭제 경합 묵상", record.reflection)
                assertEquals(3L, record.revision)
            }
            listOf(404, 204) -> assertFalse(copies.existsById(id))
            else -> fail<Unit>("Unexpected reflection/delete results: $outcomes")
        }
    }

    private fun passageFixture(texts: List<Pair<Int, String>>): Pair<String, List<BibleVerseEntity>> {
        val version = "RANGE${UUID.randomUUID().toString().take(8).uppercase()}"
        val book = books.saveAndFlush(BibleBookEntity(version = version, code = "GEN", name = "연속 필사 검증", testament = Testament.OLD, displayOrder = 1))
        chapters.saveAndFlush(BibleChapterEntity(version = version, book = book, chapter = 1, contentStatus = BibleChapterContentStatus.AVAILABLE, verseCount = texts.size))
        return version to verses.saveAllAndFlush(texts.map { (verse, text) -> BibleVerseEntity(version = version, book = book, chapter = 1, verse = verse, text = text) })
    }

    private fun completedCopy(token: String): UUID {
        val id = UUID.randomUUID()
        val response = put(token, id, request()).andExpect { status { isOk() } }.andReturn()
        val source = mapper.readTree(response.response.contentAsString)["sourceText"].asText()
        put(token, id, request(typed = source, revision = 1, complete = true)).andExpect { status { isOk() } }
        return id
    }

    private fun patch(token: String, id: UUID, reflection: String, revision: Long) = mvc.patch("/api/v1/scripture-copies/$id/reflection") {
        bearer(token); contentType = MediaType.APPLICATION_JSON
        content = mapper.writeValueAsString(mapOf("reflection" to reflection, "expectedRevision" to revision))
    }

    private fun command() = ScriptureCopyCommand("KOR1910", "GEN", 1, 1, "", "", 0, false)
    private fun request(
        typed: String = "", reflection: String = "", revision: Long = 0, complete: Boolean = false,
        version: String = "KOR1910", book: String = "GEN", verse: Int = 1, endVerse: Int? = null,
    ) = mapper.writeValueAsString(buildMap<String, Any> {
        putAll(mapOf("version" to version, "bookCode" to book, "chapter" to 1, "verse" to verse,
            "typedText" to typed, "reflection" to reflection, "expectedRevision" to revision, "complete" to complete))
        if (endVerse != null) put("endVerse", endVerse)
    })

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
            content = """{"email":"$email","password":"password123!","displayName":"${uniqueTestNickname()}"}"""
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
