package com.example.mypasture.daily_word.application

import com.example.mypasture.auth.infrastructure.UserRepository
import com.example.mypasture.bible.domain.*
import com.example.mypasture.bible.infrastructure.*
import com.example.mypasture.daily_word.domain.*
import com.example.mypasture.daily_word.infrastructure.*
import com.example.mypasture.notification.application.NotificationService
import com.example.mypasture.notification.application.PushSender
import com.example.mypasture.notification.domain.*
import com.example.mypasture.notification.infrastructure.*
import com.example.mypasture.user.domain.UserEntity
import jakarta.persistence.EntityManager
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/** 실제 PostgreSQL의 로테이션 직렬화·롤백·게시 보호·한국 날짜 알림 경계를 독립 검증합니다. */
@EnabledIfEnvironmentVariable(named = "DAILY_WORD_ROTATION_TEST_JDBC_URL", matches = ".+")
@SpringBootTest(properties = ["app.daily-word.rotation-enabled=true", "app.daily-word.generation-enabled=false", "app.bible.import-enabled=false"])
@AutoConfigureMockMvc
@Import(DailyWordRotationPostgresTests.ClockConfig::class)
class DailyWordRotationPostgresTests(
    @Autowired private val rotation: DailyWordRotationService,
    @Autowired private val service: DailyWordService,
    @Autowired private val catalog: DailyWordRotationCatalog,
    @Autowired private val words: DailyWordRepository,
    @Autowired private val jobs: DailyWordGenerationJobRepository,
    @Autowired private val books: BibleBookEntityRepository,
    @Autowired private val chapters: BibleChapterEntityRepository,
    @Autowired private val verses: BibleVerseEntityRepository,
    @Autowired private val broadcasts: NotificationBroadcastRepository,
    @Autowired private val notification: NotificationService,
    @Autowired private val deliveries: NotificationDeliveryRepository,
    @Autowired private val devices: NotificationDeviceRepository,
    @Autowired private val settings: DailyWordNotificationSettingRepository,
    @Autowired private val users: UserRepository,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val entityManager: EntityManager,
    @Autowired private val clock: TestClock,
    @Autowired private val mvc: MockMvc,
) {
    @MockitoSpyBean private lateinit var publisher: DailyWordNotificationPublisher
    @MockitoBean private lateinit var push: PushSender

    @BeforeEach
    fun prepareSyntheticDatabase() {
        assertEquals("PostgreSQL", jdbc.dataSource!!.connection.use { it.metaData.databaseProductName })
        jdbc.execute("TRUNCATE notification_device_deliveries,daily_word_notification_settings,notification_devices,notification_broadcasts,daily_word_generation_jobs,daily_words,bible_verses,bible_chapters,bible_books RESTART IDENTITY CASCADE")
        clock.set(NOW)
        // 합성 원문은 트랜잭션 논리 fixture입니다. 실제 파일 전체 후보 검증은 별도 SourceTests입니다.
        for ((code, entries) in catalog.entries.groupBy { it.bookCode }) {
            val canonical = BibleCatalog.books.single { it.code == code }
            val book = books.saveAndFlush(BibleBookEntity(version = "KOR1910", code = code, name = canonical.name,
                testament = canonical.testament, displayOrder = canonical.displayOrder))
            for ((chapter, ranges) in entries.groupBy { it.chapter }) {
                val numbers = ranges.flatMap { (it.verse..it.endVerse).toList() }.distinct()
                chapters.saveAndFlush(BibleChapterEntity(version = "KOR1910", book = book, chapter = chapter,
                    contentStatus = BibleChapterContentStatus.AVAILABLE, verseCount = numbers.size))
                verses.saveAllAndFlush(numbers.map { number -> BibleVerseEntity(version = "KOR1910", book = book,
                    chapter = chapter, verse = number, text = "QA 합성 본문 $code $chapter:$number") })
            }
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM daily_word_rotation_lock WHERE id=1", Int::class.java))
    }

    private fun source(entry: DailyWordRotationEntry, number: Int = entry.verse): BibleVerseEntity {
        val book = books.findByVersionAndCode("KOR1910", entry.bookCode)!!
        return verses.findByVersionAndBook_IdAndChapterAndVerse("KOR1910", book.id, entry.chapter, number)!!
    }

    private fun draft(date: LocalDate, sourceKind: DailyWordSource = DailyWordSource.AI,
        statusKind: DailyWordStatus = DailyWordStatus.DRAFT,
        entry: DailyWordRotationEntry = catalog.candidates(date).first()): DailyWordEntity {
        val verse = source(entry)
        return words.saveAndFlush(DailyWordEntity(wordDate = date, verseId = verse.id, bibleVersion = "KOR1910",
            bookCode = entry.bookCode, bookName = books.findByVersionAndCode("KOR1910", entry.bookCode)!!.name,
            chapter = entry.chapter, verse = entry.verse, verseText = verse.text, meditation = "이전 안내문",
            actionQuestion = "이전 질문", source = sourceKind, status = statusKind,
            published = statusKind == DailyWordStatus.PUBLISHED, model = "prior-model", promptVersion = "prior-prompt",
            promptTokens = 29, estimatedCostUsd = "0.00123".toBigDecimal()))
    }

    @Test
    fun `simultaneous first requests store one immutable snapshot and one broadcast`() {
        val executor = Executors.newFixedThreadPool(6); val ready = CountDownLatch(6); val start = CountDownLatch(1)
        try {
            val tasks = List(6) { executor.submit<UUID> { ready.countDown(); start.await(); rotation.ensurePublished(TODAY)!!.id } }
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown()
            val ids = tasks.map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(1, ids.distinct().size)
            assertEquals(1L, words.count()); assertEquals(1L, broadcasts.count())
            val saved = words.findByWordDate(TODAY)!!
            assertEquals(saved.id, rotation.ensurePublished(TODAY)!!.id)
            assertEquals(DailyWordStatus.PUBLISHED, saved.status); assertEquals(DailyWordSource.CURATED, saved.source)
            assertNull(saved.reviewedBy); assertNull(saved.reviewedAt)
            assertEquals(1L, broadcasts.count())
        } finally { start.countDown(); executor.shutdownNow() }
    }

    @Test
    fun `old first verse API remains canonical while optional passage includes complete numbered range`() {
        val word = rotation.ensurePublished(TODAY)!!
        assertEquals(source(catalog.candidates(TODAY).first()).text, word.verseText)
        assertTrue(word.passageText!!.startsWith("${word.verse}. ${word.verseText}"))
        assertTrue(word.passageText!!.contains("${word.endVerse}. QA 합성 본문"))
        mvc.get("/api/v1/today-word") .andExpect {
            status { isOk() }; jsonPath("$.verseText") { value(word.verseText) }
            jsonPath("$.passageText") { value(word.passageText!!) }
            jsonPath("$.endVerse") { value(word.endVerse!!) }
        }
        assertEquals(word.id, service.getPublished(TODAY).id)
    }

    @Test
    fun `AI draft replacement preserves prior cost and model history but cancels its active lease`() {
        val old = draft(TODAY)
        val job = jobs.saveAndFlush(DailyWordGenerationJobEntity(wordDate = TODAY).also {
            it.claim(UUID.randomUUID(), NOW.plusSeconds(600), NOW)
        })
        val word = rotation.ensurePublished(TODAY)!!
        assertEquals(old.id, word.id); assertEquals(old.contentRevision + 1, word.contentRevision)
        assertEquals("prior-model", word.model); assertEquals("prior-prompt", word.promptVersion)
        assertEquals(29, word.promptTokens); assertEquals(0, word.estimatedCostUsd.compareTo("0.00123".toBigDecimal()))
        val cancelled = jobs.findById(job.id).orElseThrow()
        assertEquals(DailyWordGenerationJobStatus.CANCELLED, cancelled.status)
        assertNull(cancelled.leaseToken); assertNull(cancelled.leaseUntil)
    }

    @Test
    fun `notification failure rolls back the persisted snapshot job cancellation and real broadcast`() {
        val old = draft(TODAY)
        val job = jobs.saveAndFlush(DailyWordGenerationJobEntity(wordDate = TODAY))
        doAnswer { call ->
            call.callRealMethod(); entityManager.flush()
            assertEquals(1L, broadcasts.count())
            throw IllegalStateException("QA induced transactional publication failure")
        }.`when`(publisher).record(any(DailyWordEntity::class.java) ?: old, any(Instant::class.java) ?: NOW)
        assertThrows(IllegalStateException::class.java) { rotation.ensurePublished(TODAY) }
        val retained = words.findById(old.id).orElseThrow()
        assertEquals(DailyWordStatus.DRAFT, retained.status); assertEquals(DailyWordSource.AI, retained.source)
        assertEquals(old.contentRevision, retained.contentRevision); assertEquals("이전 안내문", retained.meditation)
        assertEquals(DailyWordGenerationJobStatus.PENDING, jobs.findById(job.id).orElseThrow().status)
        assertEquals(0L, broadcasts.count())
    }

    @Test
    fun `published manual and rejected records survive automatic requests without new alerts`() {
        val published = draft(TODAY, DailyWordSource.MANUAL, DailyWordStatus.PUBLISHED)
        assertEquals(published.id, rotation.ensurePublished(TODAY)!!.id)
        words.deleteById(published.id)
        for ((sourceKind, statusKind) in listOf(DailyWordSource.MANUAL to DailyWordStatus.DRAFT,
            DailyWordSource.AI to DailyWordStatus.REJECTED, DailyWordSource.CURATED to DailyWordStatus.DRAFT)) {
            val protected = draft(TODAY, sourceKind, statusKind)
            assertNull(rotation.ensurePublished(TODAY))
            val stored = words.findById(protected.id).orElseThrow()
            assertEquals(sourceKind, stored.source); assertEquals(statusKind, stored.status)
            assertEquals("이전 안내문", stored.meditation); words.deleteById(protected.id)
        }
        assertEquals(0L, broadcasts.count())
    }

    @Test
    fun `incomplete and blank candidate ranges are skipped without fabricating missing Scripture`() {
        val candidates = catalog.candidates(TODAY)
        val missing = source(candidates[0], candidates[0].endVerse); verses.deleteById(missing.id)
        verses.saveAndFlush(source(candidates[1]).apply { text = "   " })
        val word = rotation.ensurePublished(TODAY)!!
        assertEquals(candidates[2].id, word.rotationEntryId)
        assertEquals(source(candidates[2]).text, word.verseText)
    }

    @Test
    fun `no valid source leaves no publication or broadcast and dates outside recovery scope remain absent`() {
        verses.deleteAllInBatch()
        assertThrows(DailyWordGenerationException::class.java) { rotation.ensurePublished(TODAY) }
        assertEquals(0L, words.count()); assertEquals(0L, broadcasts.count())
        assertNull(rotation.ensurePublished(TODAY.minusDays(1)))
        assertNull(rotation.ensurePublished(TODAY.plusDays(2)))
        mvc.get("/api/v1/today-word?date=${TODAY.minusDays(1)}").andExpect { status { isNotFound() } }
        mvc.get("/api/v1/today-word?date=${TODAY.plusDays(2)}").andExpect { status { isNotFound() } }
    }

    @Test
    fun `themed selection avoids recent overlap but returns to base order when all candidates were seen`() {
        val candidates = catalog.candidates(TODAY)
        val old = draft(TODAY.minusDays(1), DailyWordSource.MANUAL, DailyWordStatus.PUBLISHED, candidates.first())
        assertEquals(candidates[1].id, rotation.ensurePublished(TODAY)!!.rotationEntryId)
        broadcasts.deleteAllInBatch(); words.deleteAllInBatch()
        candidates.forEachIndexed { index, entry ->
            draft(TODAY.minusDays(index.toLong() + 1), DailyWordSource.MANUAL, DailyWordStatus.PUBLISHED, entry)
        }
        assertEquals(candidates.first().id, rotation.ensurePublished(TODAY)!!.rotationEntryId)
        assertEquals(1L, broadcasts.count())
        assertFalse(words.existsById(old.id))
    }

    @Test
    fun `continuous reading preserves chronological context even when its first range was recently published`() {
        val saturday = LocalDate.of(2026, 10, 10)
        clock.set(saturday.atTime(12, 0).atZone(SEOUL).toInstant())
        val first = catalog.candidates(saturday).first()
        draft(saturday.minusDays(1), DailyWordSource.MANUAL, DailyWordStatus.PUBLISHED, first)
        val word = rotation.ensurePublished(saturday)!!
        assertEquals(first.id, word.rotationEntryId)
        assertEquals(DailyWordReadingMode.CONTINUOUS, word.readingMode)
        assertEquals("", word.topicTags)
        assertEquals(first.verse, word.verse); assertEquals(first.endVerse, word.endVerse)
    }

    @Test
    fun `tomorrow broadcast is hidden until precisely midnight Korea time`() {
        val tomorrow = TODAY.plusDays(1); val word = rotation.ensurePublished(tomorrow)!!
        val event = broadcasts.findAll().single()
        val midnight = tomorrow.atStartOfDay(SEOUL).toInstant()
        assertEquals(midnight, event.publishedAt)
        assertNull(broadcasts.findVisibleById(event.id, midnight.minusNanos(1000)))
        assertNotNull(broadcasts.findVisibleById(event.id, midnight))
        assertEquals(word.id, service.getPublished(tomorrow).id)
    }

    private fun dueDevice(zone: String, time: LocalTime): UUID {
        val owner = users.saveAndFlush(UserEntity(displayName = "QA local push owner"))
        val device = devices.saveAndFlush(NotificationDeviceEntity(userId = owner.id, deviceId = UUID.randomUUID().toString(),
            fcmToken = "qa-local-token-${UUID.randomUUID()}", platform = DevicePlatform.ANDROID))
        settings.saveAndFlush(DailyWordNotificationSettingEntity(userId = owner.id, timezone = zone, notificationTime = time))
        return device.id
    }

    @Test
    fun `UTC plus 14 cannot schedule tomorrow before Korea midnight and both zones can deliver at boundary`() = runBlocking {
        val tomorrow = TODAY.plusDays(1); rotation.ensurePublished(tomorrow)
        val midnight = tomorrow.atStartOfDay(SEOUL).toInstant()
        val advanced = dueDevice("Pacific/Kiritimati", LocalTime.of(4, 59))
        val korean = dueDevice("Asia/Seoul", LocalTime.MIDNIGHT)
        clock.set(midnight.minusSeconds(60)); notification.dispatchDueDailyWords()
        assertEquals(0L, deliveries.count())
        val setting = settings.findAll().single { it.timezone == "Pacific/Kiritimati" }
        setting.notificationTime = LocalTime.of(5, 0); settings.saveAndFlush(setting)
        clock.set(midnight); notification.dispatchDueDailyWords()
        assertEquals(2L, deliveries.count())
        val word = words.findByWordDate(tomorrow)!!
        assertNotNull(deliveries.findByDailyWord_IdAndDevice_Id(word.id, advanced))
        assertNotNull(deliveries.findByDailyWord_IdAndDevice_Id(word.id, korean))
        notification.dispatchDueDailyWords(); assertEquals(2L, deliveries.count())
    }

    @Test
    fun `legacy job then word lock completes while rotation waits without inverse order deadlock`() {
        val word = draft(TODAY)
        val job = jobs.saveAndFlush(DailyWordGenerationJobEntity(wordDate = TODAY))
        val executor = Executors.newSingleThreadExecutor()
        try {
            jdbc.dataSource!!.connection.use { legacy ->
                legacy.autoCommit = false
                try {
                    legacy.createStatement().use { statement ->
                        statement.execute("SET LOCAL lock_timeout='3s'")
                        statement.executeQuery("SELECT id FROM daily_word_generation_jobs WHERE id='${job.id}' FOR UPDATE").close()
                    }
                    val next = executor.submit<DailyWordEntity?> { rotation.ensurePublished(TODAY) }
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    var waiting = false
                    while (System.nanoTime() < deadline && !waiting) {
                        waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND pid<>pg_backend_pid()", Int::class.java)!! > 0
                        if (!waiting) LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10))
                    }
                    assertTrue(waiting, "rotation must actually contend with the legacy transaction")
                    legacy.createStatement().use { statement ->
                        statement.executeQuery("SELECT id FROM daily_words WHERE id='${word.id}' FOR UPDATE").close()
                        statement.executeUpdate("UPDATE daily_words SET meditation='legacy locked write' WHERE id='${word.id}'")
                    }
                    legacy.commit()
                    assertEquals(word.id, next.get(10, TimeUnit.SECONDS)!!.id)
                    assertEquals(DailyWordStatus.PUBLISHED, words.findById(word.id).orElseThrow().status)
                    assertEquals(DailyWordGenerationJobStatus.CANCELLED, jobs.findById(job.id).orElseThrow().status)
                } finally { legacy.rollback() }
            }
        } finally { executor.shutdownNow() }
    }

    @Test
    fun `default today read crossing midnight uses one captured Korean date instead of disappearing`() {
        val boundary = TODAY.plusDays(1).atStartOfDay(SEOUL).toInstant()
        clock.advanceAfterFirstRead(boundary.minusSeconds(1), boundary)
        assertEquals(TODAY, service.getPublished().wordDate)
        assertEquals(boundary, clock.instant())
        assertEquals(1L, words.count())
        assertEquals(1L, broadcasts.count())
    }

    class TestClock : Clock() {
        private val current = AtomicReference(NOW)
        private val next = AtomicReference<Instant?>(null)
        fun set(now: Instant) { current.set(now); next.set(null) }
        fun advanceAfterFirstRead(before: Instant, after: Instant) { current.set(before); next.set(after) }
        override fun instant(): Instant {
            val before = current.get()
            next.getAndSet(null)?.let(current::set)
            return before
        }
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
    }

    @TestConfiguration class ClockConfig {
        @Bean @Primary fun clock() = TestClock()
    }

    companion object {
        val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")
        val TODAY: LocalDate = LocalDate.of(2026, 10, 5)
        val NOW: Instant = Instant.parse("2026-10-05T03:00:00Z")
        @JvmStatic @DynamicPropertySource fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { System.getenv("DAILY_WORD_ROTATION_TEST_JDBC_URL") }
            registry.add("spring.datasource.username") { System.getenv("DAILY_WORD_ROTATION_TEST_JDBC_USER") ?: "postgres" }
            registry.add("spring.datasource.password") { System.getenv("DAILY_WORD_ROTATION_TEST_JDBC_PASSWORD") ?: "" }
            registry.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
            registry.add("spring.flyway.enabled") { "true" }
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
        }
    }
}
