package com.example.mypasture.quiet_time.presentation

import com.example.mypasture.bible.domain.BibleBookEntity
import com.example.mypasture.bible.domain.BibleChapterContentStatus
import com.example.mypasture.bible.domain.BibleChapterEntity
import com.example.mypasture.bible.domain.BibleVerseEntity
import com.example.mypasture.bible.domain.Testament
import com.example.mypasture.bible.infrastructure.BibleBookEntityRepository
import com.example.mypasture.bible.infrastructure.BibleChapterEntityRepository
import com.example.mypasture.bible.infrastructure.BibleVerseEntityRepository
import com.example.mypasture.quiet_time.application.QuietTimeQuestionNotReadyException
import com.example.mypasture.quiet_time.application.QuietTimeQuestionSetService
import com.example.mypasture.quiet_time.application.QuietTimeSelectedPassageService
import com.example.mypasture.quiet_time.application.QuietTimeSharedQuestionService
import com.example.mypasture.quiet_time.application.QuietTimeService
import com.example.mypasture.quiet_time.domain.QuietTimeQuestionStyle
import com.example.mypasture.quiet_time.infrastructure.QuietTimeSharedQuestionStore
import com.example.mypasture.quiet_time.infrastructure.QuietTimeGenerationLimitException
import com.example.mypasture.auth.infrastructure.UserRepository
import com.example.mypasture.user.domain.UserEntity
import com.example.mypasture.user.application.AccountDataErasure
import com.example.mypasture.user.application.AccountDataErasureCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.annotation.DirtiesContext
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 동일 성경 절의 동시 생성과 세션별 질문 재사용을 검증합니다. */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class QuietTimeSharedQuestionTests(
    @Autowired private val selectedPassages: QuietTimeSelectedPassageService,
    @Autowired private val sharedQuestions: QuietTimeSharedQuestionService,
    @Autowired private val quietTimeService: QuietTimeService,
    @Autowired private val questionSets: QuietTimeQuestionSetService,
    @Autowired private val books: BibleBookEntityRepository,
    @Autowired private val chapters: BibleChapterEntityRepository,
    @Autowired private val verses: BibleVerseEntityRepository,
    @Autowired private val jdbc: JdbcTemplate,
    @Autowired private val users: UserRepository,
    @Autowired private val store: QuietTimeSharedQuestionStore,
    @Autowired private val accountErasure: AccountDataErasure,
) {
    @Test
    fun `same verse has one passage and one shared question set across concurrent requests`() = runBlocking {
        seedVerse(34)
        val first = selectedPassages.select("KOR1910", "JHN", 13, 34, 34)
        val second = selectedPassages.select("KOR1910", "JHN", 13, 34, 34)
        val a = users.saveAndFlush(UserEntity(displayName = "큐티질문목자A"))
        val b = users.saveAndFlush(UserEntity(displayName = "큐티질문목자B"))
        assertEquals(first.id, second.id)
        assertTrue(quietTimeService.recommendPassages(null, false, 20).none { it.id == first.id })
        assertFailsWith<QuietTimeQuestionNotReadyException> {
            questionSets.selectForSession(first, "GENERAL", null, QuietTimeQuestionStyle.BALANCED)
        }
        val results = listOf(
            async(Dispatchers.IO) { sharedQuestions.generate(first.id, QuietTimeQuestionStyle.BALANCED, a.id) },
            async(Dispatchers.IO) { sharedQuestions.generate(second.id, QuietTimeQuestionStyle.BALANCED, b.id) },
        ).map { it.await() }
        assertTrue(results.all { it.status == "READY" || it.status == "GENERATING" })
        val ready = sharedQuestions.status(first.id, QuietTimeQuestionStyle.BALANCED)
        assertEquals("READY", ready.status)
        assertEquals(ready.questionSet, questionSets.selectForSession(second, "GENERAL", null, QuietTimeQuestionStyle.BALANCED))
        assertEquals(1, jdbc.queryForObject("select count(*) from quiet_time_shared_question_sets where passage_id = ? and question_style = 'BALANCED'", Int::class.java, first.id))

        sharedQuestions.generate(first.id, QuietTimeQuestionStyle.REFLECTION, a.id)
        assertEquals(2, jdbc.queryForObject("select count(*) from quiet_time_shared_question_sets where passage_id = ? and generator_version = 2", Int::class.java, first.id))
        assertNotEquals(ready.questionSet?.style, sharedQuestions.status(first.id, QuietTimeQuestionStyle.REFLECTION).questionSet?.style)
    }

    @Test
    fun `claim lease fences stale writer and daily quota rolls back sixth claim`() {
        seedVerse(34)
        val passage = selectedPassages.select("KOR1910", "JHN", 13, 34, 34)
        val firstUser = users.saveAndFlush(UserEntity(displayName = "질문생성경합A"))
        val secondUser = users.saveAndFlush(UserEntity(displayName = "질문생성경합B"))
        val now = java.time.Instant.now()
        val hash = "a".repeat(64)
        val firstToken = assertNotNull(store.claim(passage.id, QuietTimeQuestionStyle.SHARING, 77, hash, firstUser.id, now))
        assertNull(store.claim(passage.id, QuietTimeQuestionStyle.SHARING, 77, hash, secondUser.id, now))
        store.fail(passage.id, QuietTimeQuestionStyle.SHARING, 77, hash, firstToken)
        val secondToken = assertNotNull(store.claim(passage.id, QuietTimeQuestionStyle.SHARING, 77, hash, secondUser.id, now))
        store.fail(passage.id, QuietTimeQuestionStyle.SHARING, 77, hash, firstToken)
        assertEquals("GENERATING", store.state(passage.id, QuietTimeQuestionStyle.SHARING, 77, hash)?.status)
        store.fail(passage.id, QuietTimeQuestionStyle.SHARING, 77, hash, secondToken)

        val limited = users.saveAndFlush(UserEntity(displayName = "질문생성한도"))
        repeat(5) { index ->
            assertNotNull(store.claim(passage.id, QuietTimeQuestionStyle.SHARING, 100 + index, "${index}b".padEnd(64, 'b'), limited.id, now))
        }
        assertFailsWith<QuietTimeGenerationLimitException> {
            store.claim(passage.id, QuietTimeQuestionStyle.SHARING, 106, "6b".padEnd(64, 'b'), limited.id, now)
        }
        assertEquals(5, jdbc.queryForObject("select count(*) from quiet_time_question_generation_attempts where user_id = ?", Int::class.java, limited.id))
    }

    @Test
    fun `account erasure removes generation attempts and anonymizes reusable questions`() {
        seedVerse(34)
        val passage = selectedPassages.select("KOR1910", "JHN", 13, 34, 34)
        val user = users.saveAndFlush(UserEntity(displayName = "QT생성기록삭제"))
        assertNotNull(store.claim(passage.id, QuietTimeQuestionStyle.ACTION, 199, "c".repeat(64), user.id, java.time.Instant.now()))

        accountErasure.erase(AccountDataErasureCommand(
            userId = user.id, email = null, requestedAt = java.time.Instant.now(),
        ))

        assertEquals(0, jdbc.queryForObject("select count(*) from quiet_time_question_generation_attempts where user_id = ?", Int::class.java, user.id))
        assertNull(jdbc.queryForObject(
            "select created_by_user_id from quiet_time_shared_question_sets where passage_id = ? and generator_version = 199",
            UUID::class.java, passage.id,
        ))
        assertEquals(1, jdbc.queryForObject("select count(*) from quiet_time_shared_question_sets where passage_id = ? and generator_version = 199", Int::class.java, passage.id))
    }

    private fun seedVerse(number: Int) {
        val book = books.findByVersionAndCode("KOR1910", "JHN")
            ?: books.saveAndFlush(BibleBookEntity(version = "KOR1910", code = "JHN", name = "요한복음", testament = Testament.NEW, displayOrder = 43))
        chapters.findByVersionAndBook_IdAndChapter("KOR1910", book.id, 13)
            ?: chapters.saveAndFlush(BibleChapterEntity(version = "KOR1910", book = book, chapter = 13, contentStatus = BibleChapterContentStatus.AVAILABLE, verseCount = 1))
        verses.findByVersionAndBook_IdAndChapterAndVerse("KOR1910", book.id, 13, number)
            ?: verses.saveAndFlush(BibleVerseEntity(version = "KOR1910", book = book, chapter = 13, verse = number,
                text = "새 계명을 너희에게 주노니 서로 사랑하라"))
    }

    companion object {
        private val schema = "qt_shared_${java.util.UUID.randomUUID().toString().replace("-", "") }"

        @JvmStatic @DynamicPropertySource
        fun postgres(registry: DynamicPropertyRegistry) {
            val url = System.getenv("QT_SHARED_QUESTION_TEST_JDBC_URL") ?: return
            registry.add("spring.datasource.url") { "$url${if ('?' in url) '&' else '?'}currentSchema=$schema,public" }
            registry.add("spring.datasource.username") { System.getenv("QT_SHARED_QUESTION_TEST_JDBC_USER") ?: "postgres" }
            registry.add("spring.datasource.password") { System.getenv("QT_SHARED_QUESTION_TEST_JDBC_PASSWORD") ?: "" }
            registry.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
            registry.add("spring.flyway.enabled") { "true" }
            registry.add("spring.flyway.schemas") { schema }
            registry.add("spring.flyway.default-schema") { schema }
            registry.add("spring.jpa.properties.hibernate.default_schema") { schema }
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
        }
    }
}
