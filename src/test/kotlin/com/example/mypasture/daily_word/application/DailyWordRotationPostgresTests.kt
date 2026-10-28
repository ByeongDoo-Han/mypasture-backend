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
        assertEquals(SCHEMA, jdbc.queryForObject("SELECT current_schema()", String::class.java))
        val ownedTables = listOf("notification_device_deliveries", "daily_word_notification_settings", "notification_devices",
            "notification_broadcasts", "daily_word_generation_jobs", "daily_words", "bible_verses", "bible_chapters", "bible_books")
        jdbc.execute("TRUNCATE ${ownedTables.joinToString(",") { "$SCHEMA.$it" }} RESTART IDENTITY CASCADE")
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
                verses.saveAllAndFlush(numbers.map { number ->
                    val labels = ranges.filter { number in it.verse..it.endVerse }
                        .flatMap { it.topics }.distinct().joinToString(" ") { it.label }
                    BibleVerseEntity(version = "KOR1910", book = book,
                        chapter = chapter, verse = number, text = "QA 합성 본문 $code $chapter:$number $labels")
                })
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
    fun `동시 최초 요청은 불변 스냅샷과 알림 방송을 각각 한 건만 저장한다`() {
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
    fun `새 게시물은 기존 필드와 범위 필드에 같은 한 절을 노출한다`() {
        val word = rotation.ensurePublished(TODAY)!!
        assertEquals(source(catalog.candidates(TODAY).first()).text, word.verseText)
        assertEquals(word.verse, word.endVerse)
        assertEquals(word.verseText, word.passageText)
        assertEquals(listOf(catalog.topic(TODAY).name), word.topics)
        mvc.get("/api/v1/today-word") .andExpect {
            status { isOk() }; jsonPath("$.verseText") { value(word.verseText) }
            jsonPath("$.passageText") { value(word.passageText!!) }
            jsonPath("$.endVerse") { value(word.endVerse!!) }
        }
        assertEquals(word.id, service.getPublished(TODAY).id)
    }

    @Test
    fun `AI 초안 교체는 이전 비용과 모델 이력을 보존하면서 활성 임대를 취소한다`() {
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
    fun `알림 실패는 저장한 스냅샷 작업 취소 실제 방송을 함께 롤백한다`() {
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
    fun `게시된 수동 콘텐츠와 거부된 콘텐츠는 자동 요청에도 남고 새 알림을 만들지 않는다`() {
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
    fun `불완전하거나 빈 후보 범위는 누락된 성경 구절을 지어내지 않고 건너뛴다`() {
        val candidates = catalog.candidates(TODAY)
        val missing = source(candidates[0], candidates[0].endVerse); verses.deleteById(missing.id)
        verses.saveAndFlush(source(candidates[1]).apply { text = "   " })
        val word = rotation.ensurePublished(TODAY)!!
        assertEquals(candidates[2].id, word.rotationEntryId)
        assertEquals(source(candidates[2]).text, word.verseText)
    }

    @Test
    fun `원본이 유효해도 주제가 없는 절은 건너뛴다`() {
        val candidates = catalog.candidates(TODAY)
        source(candidates[0]).let { it.text = "QA 합성 본문에 해당 주제가 없습니다"; verses.saveAndFlush(it) }
        val word = rotation.ensurePublished(TODAY)!!
        assertEquals(candidates[1].id, word.rotationEntryId)
        assertTrue(catalog.topic(TODAY).appearsIn(word.verseText))
    }

    @Test
    fun `유효한 원본이 없으면 게시와 방송이 없고 복구 범위 밖 날짜도 비워 둔다`() {
        verses.deleteAllInBatch()
        assertThrows(DailyWordGenerationException::class.java) { rotation.ensurePublished(TODAY) }
        assertEquals(0L, words.count()); assertEquals(0L, broadcasts.count())
        assertNull(rotation.ensurePublished(TODAY.minusDays(1)))
        assertNull(rotation.ensurePublished(TODAY.plusDays(2)))
        mvc.get("/api/v1/today-word?date=${TODAY.minusDays(1)}").andExpect { status { isNotFound() } }
        mvc.get("/api/v1/today-word?date=${TODAY.plusDays(2)}").andExpect { status { isNotFound() } }
    }

    @Test
    fun `주제별 선택은 최근 중복을 피하고 모두 사용했으면 기본 순서로 돌아간다`() {
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
    fun `주말 게시도 주제별 한 절을 사용하고 최근 게시 후보는 건너뛴다`() {
        val saturday = LocalDate.of(2026, 10, 10)
        clock.set(saturday.atTime(12, 0).atZone(SEOUL).toInstant())
        val first = catalog.candidates(saturday).first()
        draft(saturday.minusDays(1), DailyWordSource.MANUAL, DailyWordStatus.PUBLISHED, first)
        val word = rotation.ensurePublished(saturday)!!
        assertEquals(catalog.candidates(saturday)[1].id, word.rotationEntryId)
        assertEquals(DailyWordReadingMode.THEMED, word.readingMode)
        assertEquals(listOf(catalog.topic(saturday).name), word.topics)
        assertEquals(word.verse, word.endVerse)
        assertEquals(word.verseText, word.passageText)
    }

    @Test
    fun `내일 알림은 한국 시각 자정 전까지 숨긴다`() {
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
    fun `UTC 더하기 14에서도 한국 자정 전에 내일 알림을 예약하지 않고 경계에서 발송할 수 있다`() = runBlocking {
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
    fun `기존 작업 잠금 뒤 말씀 잠금이 끝나고 로테이션 대기도 역순 교착 없이 완료된다`() {
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
    fun `자정을 넘는 오늘 조회도 처음 잡은 한국 날짜를 유지해 결과가 사라지지 않는다`() {
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
        val SCHEMA: String = "rotation_service_${UUID.randomUUID().toString().replace("-", "") }"
        @JvmStatic @DynamicPropertySource fun database(registry: DynamicPropertyRegistry) {
            val url = System.getenv("DAILY_WORD_ROTATION_TEST_JDBC_URL")!!
            registry.add("spring.datasource.url") { "$url${if ('?' in url) '&' else '?'}currentSchema=$SCHEMA,public" }
            registry.add("spring.datasource.username") { System.getenv("DAILY_WORD_ROTATION_TEST_JDBC_USER") ?: "postgres" }
            registry.add("spring.datasource.password") { System.getenv("DAILY_WORD_ROTATION_TEST_JDBC_PASSWORD") ?: "" }
            registry.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
            registry.add("spring.flyway.enabled") { "true" }
            registry.add("spring.flyway.schemas") { SCHEMA }
            registry.add("spring.flyway.default-schema") { SCHEMA }
            registry.add("spring.jpa.properties.hibernate.default_schema") { SCHEMA }
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
        }
    }
}
