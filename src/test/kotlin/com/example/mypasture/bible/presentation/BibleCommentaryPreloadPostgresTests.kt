package com.example.mypasture.bible.presentation

import com.example.mypasture.auth.infrastructure.UserRepository
import com.example.mypasture.bible.application.BibleCommentaryBatchService
import com.example.mypasture.bible.domain.BibleCatalog
import com.example.mypasture.bible.domain.BibleChapterContentStatus
import com.example.mypasture.bible.domain.BibleChapterEntity
import com.example.mypasture.bible.domain.BibleVerseEntity
import com.example.mypasture.bible.infrastructure.BibleBookEntityRepository
import com.example.mypasture.bible.infrastructure.BibleChapterEntityRepository
import com.example.mypasture.bible.infrastructure.BibleCommentaryGenerationJobRepository
import com.example.mypasture.bible.infrastructure.BibleVerseEntityRepository
import com.example.mypasture.user.domain.UserEntity
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.annotation.DirtiesContext
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.assertEquals

/** 실제 PostgreSQL과 Flyway 스키마에서 사전 생성 범위의 영속적 중복 방지를 검증합니다. */
@EnabledIfEnvironmentVariable(named = "COMMENTARY_PRELOAD_TEST_JDBC_URL", matches = ".+")
@SpringBootTest(properties = ["app.bible.import-enabled=false", "spring.datasource.hikari.maximum-pool-size=2"])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class BibleCommentaryPreloadPostgresTests(
    @Autowired private val batchService: BibleCommentaryBatchService,
    @Autowired private val jobRepository: BibleCommentaryGenerationJobRepository,
    @Autowired private val bookRepository: BibleBookEntityRepository,
    @Autowired private val chapterRepository: BibleChapterEntityRepository,
    @Autowired private val verseRepository: BibleVerseEntityRepository,
    @Autowired private val userRepository: UserRepository,
    @Autowired private val jdbc: JdbcTemplate,
) {
    @Test
    fun `범위 요청을 반복해도 유효한 장마다 활성 작업 한 건만 저장한다`() {
        assertEquals("PostgreSQL", jdbc.dataSource!!.connection.use { it.metaData.databaseProductName })
        val user = userRepository.saveAndFlush(UserEntity(displayName = "해설 사전 생성 검증 관리자"))
        val genesis = BibleCatalog.findBook("GEN")!!
        val book = bookRepository.findByVersionAndCode("KOR1910", genesis.code)!!
        // Flyway V5가 창세기 1장 본문을 적재합니다. 두 번째 장만 합성 본문을 더합니다.
        for (number in 2..2) {
            chapterRepository.saveAndFlush(BibleChapterEntity(
                version = "KOR1910", book = book, chapter = number,
                contentStatus = BibleChapterContentStatus.AVAILABLE, verseCount = 1,
            ))
            verseRepository.saveAndFlush(BibleVerseEntity(
                version = "KOR1910", book = book, chapter = number, verse = 1,
                text = "장별 해설 사전 생성 테스트 본문 $number",
            ))
        }

        val first = batchService.queueRangeGeneration("KOR1910", "GEN", 1, 3, false, user.id)
        assertEquals(listOf(1, 2), first.queuedChapters)
        assertEquals(listOf(3), first.unavailableChapters)
        val repeated = batchService.queueRangeGeneration("KOR1910", "GEN", 1, 3, false, user.id)
        assertEquals(emptyList(), repeated.queuedChapters)
        assertEquals(listOf(1, 2), repeated.skippedChapters)
        assertEquals(2L, jobRepository.count())
        assertEquals("PENDING", batchService.getBookCoverage("KOR1910", "GEN").chapters[0].status.name)
    }

    companion object {
        private val schema = "commentary_preload_${UUID.randomUUID().toString().replace("-", "") }"

        @JvmStatic @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            val url = System.getenv("COMMENTARY_PRELOAD_TEST_JDBC_URL")!!
            registry.add("spring.datasource.url") { "$url${if ('?' in url) '&' else '?'}currentSchema=$schema,public" }
            registry.add("spring.datasource.username") { System.getenv("COMMENTARY_PRELOAD_TEST_JDBC_USER") ?: "postgres" }
            registry.add("spring.datasource.password") { System.getenv("COMMENTARY_PRELOAD_TEST_JDBC_PASSWORD") ?: "" }
            registry.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
            registry.add("spring.flyway.enabled") { "true" }
            registry.add("spring.flyway.schemas") { schema }
            registry.add("spring.flyway.default-schema") { schema }
            registry.add("spring.jpa.properties.hibernate.default_schema") { schema }
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
        }
    }
}
