package com.example.mypasture.achievement

import com.example.mypasture.achievement.application.AchievementService
import com.example.mypasture.achievement.infrastructure.AchievementAwardRepository
import com.example.mypasture.achievement.infrastructure.NoteActivityDayRepository
import com.example.mypasture.auth.infrastructure.JwtTokenProvider
import com.example.mypasture.auth.infrastructure.UserRepository
import com.example.mypasture.bible.application.BibleBookmarkService
import com.example.mypasture.bible.application.BibleNoteService
import com.example.mypasture.bible.application.BibleNoteVerseSelection
import com.example.mypasture.bible.domain.BibleBookEntity
import com.example.mypasture.bible.domain.BibleChapterEntity
import com.example.mypasture.bible.domain.BibleChapterContentStatus
import com.example.mypasture.bible.domain.BibleNoteEntity
import com.example.mypasture.bible.domain.BibleVerseEntity
import com.example.mypasture.bible.domain.Testament
import com.example.mypasture.bible.infrastructure.BibleBookEntityRepository
import com.example.mypasture.bible.infrastructure.BibleChapterEntityRepository
import com.example.mypasture.bible.infrastructure.BibleNoteRepository
import com.example.mypasture.bible.infrastructure.BibleVerseEntityRepository
import com.example.mypasture.reading.application.ReadingService
import com.example.mypasture.reading.domain.BibleReadingHistoryEntity
import com.example.mypasture.reading.domain.BibleReadingStateEntity
import com.example.mypasture.reading.infrastructure.BibleReadingHistoryRepository
import com.example.mypasture.reading.infrastructure.BibleReadingStateRepository
import com.example.mypasture.user.application.AccountDataErasure
import com.example.mypasture.user.application.AccountDataErasureCommand
import com.example.mypasture.user.domain.UserEntity
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** 실제 저장소와 인증 API로 소급·원본 연동·멱등 수여·삭제·경합을 검증합니다. */
@SpringBootTest(properties = ["app.bible.import-enabled=true"])
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(AchievementControllerTests.TestClockConfiguration::class)
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AchievementControllerTests(
    @Autowired private val mvc: MockMvc,
    @Autowired private val users: UserRepository,
    @Autowired private val jwt: JwtTokenProvider,
    @Autowired private val service: AchievementService,
    @Autowired private val reading: ReadingService,
    @Autowired private val history: BibleReadingHistoryRepository,
    @Autowired private val states: BibleReadingStateRepository,
    @Autowired private val awards: AchievementAwardRepository,
    @Autowired private val noteDays: NoteActivityDayRepository,
    @Autowired private val notes: BibleNoteRepository,
    @Autowired private val noteService: BibleNoteService,
    @Autowired private val bookmarks: BibleBookmarkService,
    @Autowired private val books: BibleBookEntityRepository,
    @Autowired private val chapters: BibleChapterEntityRepository,
    @Autowired private val verses: BibleVerseEntityRepository,
    @Autowired private val erasure: AccountDataErasure,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val transactionManager: PlatformTransactionManager,
) {
    @BeforeAll
    fun installCompositeForeignKeyForHibernateTestSchema() {
        val isH2 = jdbc.dataSource!!.connection.use { it.metaData.databaseProductName.equals("H2", ignoreCase = true) }
        if (isH2) {
            // PostgreSQL은 V42.7 Flyway가 같은 제약을 생성하며 테스트에서 변경하지 않습니다.
            jdbc.execute("""alter table user_achievement_featured
                add constraint if not exists fk_user_achievement_featured_award
                foreign key (user_id, achievement_code)
                references user_achievement_awards(user_id, achievement_code) on delete cascade""")
        }
    }

    @Test fun `database rejects featuring an unearned achievement without application validation`() {
        val user = newUser()
        assertFailsWith<DataIntegrityViolationException> {
            jdbc.update("insert into user_achievement_featured (id, user_id, achievement_code, position) values (?, ?, ?, ?)", UUID.randomUUID(), user.id, "FIRST_CHAPTER", 0)
        }
        assertTrue(service.summary(user.id).featuredCodes.isEmpty())
    }

    @Test fun `anonymous endpoints are rejected and requested userId cannot change ownership`() {
        mvc.get("/api/v1/achievements").andExpect { status { isUnauthorized() } }
        mvc.post("/api/v1/achievements/reconcile").andExpect { status { isUnauthorized() } }
        mvc.put("/api/v1/achievements/featured") { contentType = MediaType.APPLICATION_JSON; content = """{"codes":[]}""" }.andExpect { status { isUnauthorized() } }
        val owner = newUser(); val other = newUser()
        seedReading(owner)
        val token = jwt.issueAccessToken(other).accessToken
        mvc.post("/api/v1/achievements/reconcile?userId=${owner.id}") { header("Authorization", "Bearer $token") }
            .andExpect { status { isOk() }; jsonPath("$.userId") { value(other.id.toString()) }; jsonPath("$.earnedCount") { value(0) } }
        assertTrue(awards.findAllByUser_Id(owner.id).isEmpty())
    }

    @Test fun `GET shows capped progress without awarding and reconciliation preserves first award time`() {
        val user = newUser(); seedReading(user)
        assertEquals(0, service.summary(user.id).earnedCount)
        assertTrue(awards.findAllByUser_Id(user.id).isEmpty())
        val first = service.reconcile(user.id)
        assertEquals(setOf("FIRST_CHAPTER", "READ_DAYS_7", "READ_STREAK_7"), first.newlyAwardedCodes.toSet())
        assertTrue(first.items.filter { it.awardedAt != null }.all { it.awardedAt == NOW && it.progress == it.target })
        val again = service.reconcile(user.id)
        assertTrue(again.newlyAwardedCodes.isEmpty())
        assertEquals(first.items, again.items)
    }

    @Test fun `opening a chapter earns nothing but the reading API awards first completion atomically`() {
        val user = newUser(); val token = jwt.issueAccessToken(user).accessToken
        fun record(verse: Int) = mvc.put("/api/v1/reading/progress") {
            header("Authorization", "Bearer $token"); contentType = MediaType.APPLICATION_JSON
            content = """{"version":"KOR1910","bookCode":"GEN","chapter":1,"lastVerse":$verse,"timezone":"Asia/Seoul"}"""
        }.andExpect { status { isOk() } }
        record(1)
        assertEquals(0, service.summary(user.id).earnedCount)
        record(2); record(1); record(2)
        assertEquals(listOf("FIRST_CHAPTER"), awards.findAllByUser_Id(user.id).map { it.achievementCode })
        assertEquals(1, service.summary(user.id).items.single { it.code == "READ_DAYS_7" }.progress)
    }

    @Test fun `noncanonical and unfinished history cannot award reading day or streak badges`() {
        val user = newUser()
        states.saveAll(listOf(
            BibleReadingStateEntity(userId = user.id, bibleVersion = "KOR1910", bookCode = "UNKNOWN", chapter = 1, lastVerse = 1, completed = true),
            BibleReadingStateEntity(userId = user.id, bibleVersion = "KOR1910", bookCode = "GEN", chapter = 51, lastVerse = 1, completed = true),
        ))
        history.saveAll((0L..6L).flatMap { day ->
            val date = LocalDate.of(2026, 1, 1).plusDays(day)
            listOf(
                BibleReadingHistoryEntity(userId = user.id, bibleVersion = "KOR1910", bookCode = "UNKNOWN", chapter = 1, readOn = date, lastVerse = 1, completed = true),
                BibleReadingHistoryEntity(userId = user.id, bibleVersion = "KOR1910", bookCode = "GEN", chapter = 51, readOn = date, lastVerse = 1, completed = true),
                BibleReadingHistoryEntity(userId = user.id, bibleVersion = "KOR1910", bookCode = "GEN", chapter = 1, readOn = date, lastVerse = 1, completed = false),
            )
        })
        val result = service.reconcile(user.id)
        assertTrue(result.newlyAwardedCodes.isEmpty())
        assertTrue(result.items.filter { it.category.name.startsWith("READING") || it.code == "FIRST_CHAPTER" }.all { it.progress == 0 })
    }

    @Test fun `featured badges require earned ownership preserve order and support idempotent clearing`() {
        val user = newUser(); seedReading(user); service.reconcile(user.id)
        val token = jwt.issueAccessToken(user).accessToken
        fun choose(body: String, ok: Boolean) {
            mvc.put("/api/v1/achievements/featured") { header("Authorization", "Bearer $token"); contentType = MediaType.APPLICATION_JSON; content = body }
                .andExpect {
                    status { if (ok) isOk() else isBadRequest() }
                    if (!ok) {
                        jsonPath("$.code") { value("INVALID_REQUEST") }
                        jsonPath("$.path") { value("/api/v1/achievements/featured") }
                    }
                }
        }
        val other = newUser()
        jdbc.update("insert into user_achievement_awards (id, user_id, achievement_code, awarded_at, rule_version) values (?, ?, ?, ?, ?)", UUID.randomUUID(), other.id, "BOOKMARKS_5", java.sql.Timestamp.from(NOW), 1)
        choose("""{"codes":["BOOKMARKS_5"]}""", false)
        choose("""{"codes":["FIRST_CHAPTER","FIRST_CHAPTER"]}""", false)
        choose("""{"codes":["FIRST_CHAPTER","READ_DAYS_7","READ_STREAK_7","FIRST_BOOK"]}""", false)
        repeat(2) { choose("""{"codes":["READ_STREAK_7","FIRST_CHAPTER"]}""", true) }
        assertEquals(listOf("READ_STREAK_7", "FIRST_CHAPTER"), service.summary(user.id).featuredCodes)
        choose("""{"codes":["FIRST_CHAPTER","READ_STREAK_7"]}""", true)
        assertEquals(listOf("FIRST_CHAPTER", "READ_STREAK_7"), service.summary(user.id).featuredCodes)
        choose("""{"codes":[]}""", true)
        assertTrue(service.summary(user.id).featuredCodes.isEmpty())
    }

    @Test fun `simultaneous reconciliation awards each badge once`() {
        val user = newUser(); seedReading(user)
        val pool = Executors.newFixedThreadPool(6); val ready = CountDownLatch(6); val start = CountDownLatch(1)
        try {
            val futures = (1..6).map { pool.submit<List<String>> { ready.countDown(); check(start.await(20, TimeUnit.SECONDS)); service.reconcile(user.id).newlyAwardedCodes } }
            check(ready.await(20, TimeUnit.SECONDS)); start.countDown()
            val issued = futures.flatMap { it.get(30, TimeUnit.SECONDS) }
            assertEquals(3, issued.size); assertEquals(3, issued.distinct().size)
            assertEquals(3, awards.findAllByUser_Id(user.id).size)
        } finally { start.countDown(); pool.shutdownNow() }
    }

    @Test fun `six concurrent first reading writes persist one state history and award`() {
        val user = newUser()
        val pool = Executors.newFixedThreadPool(6); val ready = CountDownLatch(6); val start = CountDownLatch(1)
        try {
            val futures = (1..6).map {
                pool.submit<UUID> {
                    ready.countDown(); check(start.await(20, TimeUnit.SECONDS))
                    reading.record(user.id, "KOR1910", "GEN", 1, 2, true, "Asia/Seoul").id
                }
            }
            check(ready.await(20, TimeUnit.SECONDS)); start.countDown()
            assertEquals(1, futures.map { it.get(30, TimeUnit.SECONDS) }.toSet().size)
            val savedStates = states.findAllByUserIdOrderByLastReadAtDesc(user.id)
            val day = LocalDate.of(2026, 10, 1)
            val savedHistory = history.findAllByUserIdAndReadOnBetweenOrderByReadOnDescUpdatedAtDesc(user.id, day, day)
            assertEquals(1, savedStates.size); assertTrue(savedStates.single().completed)
            assertEquals(1, savedHistory.size); assertTrue(savedHistory.single().completed)
            assertEquals(listOf("FIRST_CHAPTER"), awards.findAllByUser_Id(user.id).map { it.achievementCode })
        } finally { start.countDown(); pool.shutdownNow() }
    }

    @Test fun `database award write failure rolls back source records and permits a clean retry`() {
        val user = newUser()
        // Scope the injected failure to this fixture user so existing test awards remain valid.
        val constraint = "ck_test_achievement_award_failure"
        jdbc.execute("alter table user_achievement_awards add constraint $constraint check (user_id <> '${user.id}' or achievement_code <> 'FIRST_CHAPTER')")
        try {
            val failure = assertFailsWith<RuntimeException> {
                reading.record(user.id, "KOR1910", "GEN", 1, 2, true, "Asia/Seoul")
            }
            assertTrue(generateSequence(failure as Throwable) { it.cause }.any { it.message?.lowercase()?.contains(constraint) == true }, "The injected DB constraint must cause the failure")
            assertTrue(states.findAllByUserIdOrderByLastReadAtDesc(user.id).isEmpty())
            val day = LocalDate.of(2026, 10, 1)
            assertTrue(history.findAllByUserIdAndReadOnBetweenOrderByReadOnDescUpdatedAtDesc(user.id, day, day).isEmpty())
            assertTrue(awards.findAllByUser_Id(user.id).isEmpty())
        } finally {
            jdbc.execute("alter table user_achievement_awards drop constraint $constraint")
        }
        reading.record(user.id, "KOR1910", "GEN", 1, 2, true, "Asia/Seoul")
        assertEquals(listOf("FIRST_CHAPTER"), awards.findAllByUser_Id(user.id).map { it.achievementCode })
    }

    @Test fun `book completion deduplicates translations and requires every canonical chapter in the database`() {
        val user = newUser()
        fun row(version: String, code: String, chapter: Int) = BibleReadingStateEntity(
            userId = user.id, bibleVersion = version, bookCode = code, chapter = chapter, lastVerse = 1, completed = true,
        )
        states.saveAll(listOf("KOR1910", "TEST_TRANSLATION").flatMap { version -> (1..49).map { row(version, "GEN", it) } } + listOf(
            row("KOR1910", "GEN", 51), row("KOR1910", "UNKNOWN", 1), row("KOR1910", "OBA", 2),
        ))
        val incomplete = service.reconcile(user.id).items.single { it.code == "FIRST_BOOK" }
        assertEquals(0, incomplete.progress); assertNull(incomplete.awardedAt)
        // The final required chapter may be completed in another translation.
        states.save(row("TEST_TRANSLATION", "GEN", 50))
        val completed = service.reconcile(user.id)
        assertEquals(listOf("FIRST_BOOK"), completed.newlyAwardedCodes)
        assertEquals(1, completed.items.single { it.code == "FIRST_BOOK" }.progress)
        assertNotNull(completed.items.single { it.code == "FIRST_BOOK" }.awardedAt)
        assertTrue(service.reconcile(user.id).newlyAwardedCodes.isEmpty())
    }

    @Test fun `source and awards roll back together`() {
        val user = newUser()
        assertFailsWith<IllegalStateException> {
            TransactionTemplate(transactionManager).executeWithoutResult {
                reading.record(user.id, "KOR1910", "GEN", 1, 2, true, "Asia/Seoul")
                check(service.summary(user.id).earnedCount == 1)
                error("Force enclosing transaction rollback")
            }
        }
        assertEquals(0, service.summary(user.id).earnedCount)
        assertTrue(history.findAllByUserIdAndReadOnBetweenOrderByReadOnDescUpdatedAtDesc(user.id, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)).isEmpty())
        assertTrue(states.findAllByUserIdOrderByLastReadAtDesc(user.id).isEmpty())
    }

    @Test fun `memo creation uses Korean date editing does not add a day and deleting preserves old evidence`() {
        val user = newUser()
        val old = listOf("2026-09-27T15:01:00Z", "2026-09-28T15:01:00Z").map { date ->
            notes.save(BibleNoteEntity(userId = user.id, content = "기존 메모", createdAt = Instant.parse(date)))
        }
        // Deletion itself must preserve historical dates before physical removal.
        noteService.delete(user.id, old.first().id)
        assertEquals(setOf(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 9, 29)), noteDays.findAllByUser_Id(user.id).map { it.recordedOn }.toSet())
        val selection = listOf(BibleNoteVerseSelection("KOR1910", "GEN", 1, 1))
        val created = noteService.create(user.id, "오늘 메모", selection)
        noteService.update(user.id, created.note.id, created.note.revision, "수정한 메모", selection)
        noteService.create(user.id, "같은 날 다른 메모", selection)
        assertEquals(3, noteDays.findAllByUser_Id(user.id).size)
        assertTrue(noteDays.findAllByUser_Id(user.id).any { it.recordedOn == LocalDate.of(2026, 10, 1) })
        noteService.delete(user.id, created.note.id)
        assertNotNull(service.summary(user.id).items.single { it.code == "NOTE_DAYS_3" }.awardedAt)
        assertEquals(3, service.summary(user.id).items.single { it.code == "NOTE_DAYS_3" }.progress)
    }

    @Test fun `bookmark threshold is current distinct positions and the badge survives deletion`() {
        val user = newUser()
        val book = books.findByVersionAndCode("KOR1910", "OBA")!!
        for (number in 1..5) {
            if (verses.findByVersionAndBook_IdAndChapterAndVerse("KOR1910", book.id, 1, number) == null) verses.save(BibleVerseEntity(version = "KOR1910", book = book, chapter = 1, verse = number, text = "테스트 말씀 $number"))
        }
        val translatedBook = books.findByVersionAndCode("TEST_TRANSLATION", "OBA") ?: books.save(BibleBookEntity(version = "TEST_TRANSLATION", code = "OBA", name = "오바댜", testament = Testament.OLD, displayOrder = 31))
        // 실제 PostgreSQL의 절→장 FK를 만족하는 유효한 번역본 장을 먼저 적재합니다.
        if (chapters.findByVersionAndBook_IdAndChapter("TEST_TRANSLATION", translatedBook.id, 1) == null) {
            chapters.saveAndFlush(BibleChapterEntity(version = "TEST_TRANSLATION", book = translatedBook, chapter = 1, contentStatus = BibleChapterContentStatus.AVAILABLE, verseCount = 4))
        }
        for (number in 1..4) {
            if (verses.findByVersionAndBook_IdAndChapterAndVerse("TEST_TRANSLATION", translatedBook.id, 1, number) == null) verses.save(BibleVerseEntity(version = "TEST_TRANSLATION", book = translatedBook, chapter = 1, verse = number, text = "번역 fixture $number"))
            bookmarks.save(user.id, "KOR1910", "OBA", 1, number)
            bookmarks.save(user.id, "TEST_TRANSLATION", "OBA", 1, number)
        }
        assertEquals(4, service.summary(user.id).items.single { it.code == "BOOKMARKS_5" }.progress)
        bookmarks.save(user.id, "KOR1910", "OBA", 1, 5)
        val first = service.summary(user.id).items.single { it.code == "BOOKMARKS_5" }.awardedAt
        assertNotNull(first)
        repeat(2) { bookmarks.delete(user.id, "KOR1910", "OBA", 1, 5); bookmarks.save(user.id, "KOR1910", "OBA", 1, 5) }
        bookmarks.delete(user.id, "KOR1910", "OBA", 1, 5)
        val summary = service.summary(user.id)
        assertEquals(first, summary.items.single { it.code == "BOOKMARKS_5" }.awardedAt)
        assertEquals(5, summary.items.single { it.code == "BOOKMARKS_5" }.progress)
        assertEquals(4, summary.items.single { it.code == "BOOKMARKS_20" }.progress)
    }

    @Test fun `memo award persistence failure rolls back memo links and activity days`() {
        val user = newUser()
        notes.saveAll(listOf("2026-09-27T15:01:00Z", "2026-09-28T15:01:00Z").map {
            BibleNoteEntity(userId = user.id, content = "기존 메모", createdAt = Instant.parse(it))
        })
        val constraint = "ck_test_note_achievement_failure"
        jdbc.execute("alter table user_achievement_awards add constraint $constraint check (user_id <> '${user.id}' or achievement_code <> 'NOTE_DAYS_3')")
        try {
            assertFailsWith<RuntimeException> {
                noteService.create(user.id, "새 메모", listOf(BibleNoteVerseSelection("KOR1910", "GEN", 1, 1)))
            }
            assertEquals(2, jdbc.queryForObject("select count(*) from bible_notes where user_id = ?", Int::class.java, user.id))
            assertEquals(0, jdbc.queryForObject("select count(*) from bible_note_verses link join bible_notes note on note.id = link.note_id where note.user_id = ?", Int::class.java, user.id))
            assertTrue(noteDays.findAllByUser_Id(user.id).isEmpty())
            assertTrue(awards.findAllByUser_Id(user.id).isEmpty())
        } finally { jdbc.execute("alter table user_achievement_awards drop constraint $constraint") }
        noteService.create(user.id, "재시도 메모", listOf(BibleNoteVerseSelection("KOR1910", "GEN", 1, 1)))
        assertEquals(listOf("NOTE_DAYS_3"), awards.findAllByUser_Id(user.id).map { it.achievementCode })
    }

    @Test fun `account erasure removes featured awards and memo day evidence`() {
        val user = newUser(); seedReading(user); service.reconcile(user.id)
        val token = jwt.issueAccessToken(user).accessToken
        service.setFeatured(user.id, listOf("FIRST_CHAPTER"))
        noteService.create(user.id, "삭제할 메모", listOf(BibleNoteVerseSelection("KOR1910", "GEN", 1, 1)))
        TransactionTemplate(transactionManager).executeWithoutResult {
            val locked = service.lockUser(user.id); locked.withdraw(NOW); users.saveAndFlush(locked)
            erasure.erase(AccountDataErasureCommand(userId = user.id, email = user.email, requestedAt = NOW))
        }
        for (table in listOf("user_achievement_awards", "user_achievement_featured", "user_note_activity_days")) assertEquals(0, jdbc.queryForObject("select count(*) from $table where user_id = ?", Int::class.java, user.id))
        mvc.get("/api/v1/achievements") { header("Authorization", "Bearer $token") }.andExpect { status { isUnauthorized() } }
        mvc.post("/api/v1/achievements/reconcile") { header("Authorization", "Bearer $token") }.andExpect { status { isUnauthorized() } }
        mvc.put("/api/v1/achievements/featured") {
            header("Authorization", "Bearer $token"); contentType = MediaType.APPLICATION_JSON; content = """{"codes":[]}"""
        }.andExpect { status { isUnauthorized() } }
        assertFailsWith<IllegalStateException> { service.reconcile(user.id) }
    }

    private fun newUser() = users.save(UserEntity(displayName = "업적 테스트"))
    private fun seedReading(user: UserEntity) {
        states.save(BibleReadingStateEntity(userId = user.id, bibleVersion = "KOR1910", bookCode = "GEN", chapter = 1, lastVerse = 2, completed = true))
        history.saveAll((0L..6L).map { BibleReadingHistoryEntity(userId = user.id, bibleVersion = "KOR1910", bookCode = "GEN", chapter = 1, readOn = LocalDate.of(2023, 12, 28).plusDays(it), lastVerse = 2, completed = true) })
    }

    /** UTC와 한국 날짜가 다른 시각을 고정해 날짜 변환과 최초 수여 시간을 검증합니다. */
    @TestConfiguration
    class TestClockConfiguration { @Bean fun achievementClock(): Clock = Clock.fixed(NOW, ZoneOffset.UTC) }

    companion object {
        val NOW: Instant = Instant.parse("2026-09-30T15:30:00Z")
        @JvmStatic @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            val url = System.getenv("ACHIEVEMENT_TEST_JDBC_URL") ?: return
            registry.add("spring.datasource.url") { url }
            registry.add("spring.datasource.username") { System.getenv("ACHIEVEMENT_TEST_JDBC_USER") ?: "postgres" }
            registry.add("spring.datasource.password") { System.getenv("ACHIEVEMENT_TEST_JDBC_PASSWORD") ?: "" }
            registry.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
            registry.add("spring.flyway.enabled") { "true" }
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
        }
    }
}
