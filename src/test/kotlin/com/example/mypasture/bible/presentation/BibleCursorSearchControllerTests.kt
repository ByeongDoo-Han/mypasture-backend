package com.example.mypasture.bible.presentation

import com.example.mypasture.bible.application.BibleSearchCursorCodec
import com.example.mypasture.bible.domain.BibleSearchPosition
import com.example.mypasture.bible.domain.BibleVerseEntity
import com.example.mypasture.bible.infrastructure.BibleBookEntityRepository
import com.example.mypasture.bible.infrastructure.BibleVerseEntityRepository
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.persistence.EntityManagerFactory
import org.hibernate.SessionFactory
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.transaction.annotation.Transactional
import java.text.Normalizer
import kotlin.test.*

/** 실제 공개 HTTP 검색과 저장소의 정렬·포함 검색·cursor 오류를 H2 또는 PostgreSQL에서 검증합니다. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = ["app.bible.kor1910-html-path=src/test/resources/bible/kor_html", "app.bible.import-enabled=true"])
@Transactional
class BibleCursorSearchControllerTests(
    @Autowired private val mvc: MockMvc,
    @Autowired private val mapper: ObjectMapper,
    @Autowired private val books: BibleBookEntityRepository,
    @Autowired private val verses: BibleVerseEntityRepository,
    @Autowired private val entityManagerFactory: EntityManagerFactory,
) {
    @Test fun `one Korean character returns book matches and every verse exactly once in canonical order`() {
        val fixtures = (1..24).map { fixture("GEN", 49, it, "요 검색검증 $it") } +
            (1..2).map { fixture("GEN", 50, it, "요 검색검증 $it") } +
            (1..19).map { fixture("JHN", 1, it, "요 검색검증 $it") }
        verses.saveAllAndFlush(fixtures.reversed())
        val expected = fixtures.map { "${it.book.code}:${it.chapter}:${it.verse}" }
        val found = mutableListOf<String>()
        val pageSizes = mutableListOf<Int>()
        var cursor: String? = null
        do {
            val page = search("요", cursor)
            assertEquals(listOf("JOL", "JON", "JHN", "1JN", "2JN", "3JN", "REV"), page["books"].map { it["code"].asText() })
            found += page["results"].map { "${it["bookCode"].asText()}:${it["chapter"].asInt()}:${it["verse"].asInt()}" }
            pageSizes += page["results"].size()
            cursor = page["nextCursor"].takeUnless { it.isNull }?.asText()
            assertEquals(cursor != null, page["hasNext"].asBoolean())
            assertTrue(pageSizes.size <= 3, "Search must terminate without repeating a cursor")
        } while (cursor != null)
        assertEquals(listOf(20, 20, 5), pageSizes)
        assertEquals(expected, found)
        assertEquals(found.size, found.distinct().size)
    }

    @Test fun `wildcards escape character and backslash are literal and Latin matching ignores case`() {
        val texts = listOf("표시 100% literal", "밑줄 _ literal", "기호 ! literal", "역슬래시 \\ literal", "MiXeD casing")
        verses.saveAllAndFlush(texts.mapIndexed { index, text -> fixture("GEN", 49, index + 1, text) })
        for ((keyword, text) in listOf("%", "_", "!", "\\", "mixed").zip(texts)) {
            val page = search(keyword)
            assertEquals(listOf(text), page["results"].map { it["text"].asText() })
            assertFalse(page["hasNext"].asBoolean()); assertTrue(page["nextCursor"].isNull)
        }
    }

    @Test fun `default and changed page size normalize keyword and last or empty pages have no cursor`() {
        verses.saveAllAndFlush((1..3).map { fixture("GEN", 49, it, "요 정규화 $it") })
        val normalized = search(" ${Normalizer.normalize("요", Normalizer.Form.NFD)} ", size = 1, version = "kor1910")
        assertEquals("요", normalized["keyword"].asText()); assertEquals("KOR1910", normalized["version"].asText())
        val last = search("요", normalized["nextCursor"].asText(), size = 50)
        assertEquals(2, last["results"].size()); assertFalse(last["hasNext"].asBoolean()); assertTrue(last["nextCursor"].isNull)
        val empty = search("없는본문ZZZ")
        assertEquals(20, empty["size"].asInt()); assertEquals(0, empty["books"].size()); assertEquals(0, empty["results"].size())
        assertFalse(empty["hasNext"].asBoolean()); assertTrue(empty["nextCursor"].isNull)
        // An exact page boundary must not invent a next page.
        val exact = search("요", size = 3)
        assertFalse(exact["hasNext"].asBoolean()); assertTrue(exact["nextCursor"].isNull)
    }

    @Test fun `invalid input and crossed cursors produce safe domain errors while unsupported version is not found`() {
        val token = BibleSearchCursorCodec.encode("KOR1910", "요", BibleSearchPosition(1, 1, 1, 1))
        for (keyword in listOf("", "   ", "가".repeat(51))) invalid(keyword = keyword)
        for (size in listOf(0, 51)) invalid(size = size)
        for (cursor in listOf("", "invalid!", "a".repeat(513), token)) invalid(keyword = "다른검색어", cursor = cursor)
        invalid(cursor = BibleSearchCursorCodec.encode("OTHER", "요", BibleSearchPosition(1, 1, 1, 1)))
        invalid(cursor = BibleSearchCursorCodec.encode("KOR1910", "요", BibleSearchPosition(67, 1, 1, 1)))
        mvc.get("/api/v1/bibles/UNKNOWN/search/cursor") { param("keyword", "요") }.andExpect {
            status { isNotFound() }; jsonPath("$.code") { value("BIBLE_NOT_FOUND") }
        }
    }

    @Test fun `cursor page fetches verses and books in one bounded query without count or N plus one`() {
        verses.saveAllAndFlush((1..21).map { fixture("GEN", 49, it, "검색성능검증 $it") })
        val statistics = entityManagerFactory.unwrap(SessionFactory::class.java).statistics
        val wasEnabled = statistics.isStatisticsEnabled
        try {
            statistics.isStatisticsEnabled = true; statistics.clear()
            val page = search("검색성능검증")
            assertEquals(20, page["results"].size()); assertTrue(page["hasNext"].asBoolean())
            assertEquals(1L, statistics.prepareStatementCount)
            assertEquals(1L, statistics.queryExecutionCount)
            assertFalse(statistics.queries.any { it.contains("count(", ignoreCase = true) })
        } finally { statistics.isStatisticsEnabled = wasEnabled }
    }

    private fun fixture(code: String, chapter: Int, number: Int, text: String) = BibleVerseEntity(
        version = "KOR1910", book = books.findByVersionAndCode("KOR1910", code)!!, chapter = chapter, verse = number, text = text,
    )

    private fun search(keyword: String, cursor: String? = null, size: Int? = null, version: String = "KOR1910"): JsonNode {
        val result = mvc.get("/api/v1/bibles/$version/search/cursor") {
            param("keyword", keyword); if (cursor != null) param("cursor", cursor); if (size != null) param("size", size.toString())
        }.andExpect { status { isOk() } }.andReturn()
        return mapper.readTree(result.response.contentAsString)
    }

    private fun invalid(keyword: String = "요", size: Int = 20, cursor: String? = null) {
        mvc.get("/api/v1/bibles/KOR1910/search/cursor") {
            param("keyword", keyword); param("size", size.toString()); if (cursor != null) param("cursor", cursor)
        }.andExpect { status { isBadRequest() }; jsonPath("$.code") { value("INVALID_BIBLE_REQUEST") } }
    }

    companion object {
        @JvmStatic @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            val url = System.getenv("BIBLE_SEARCH_TEST_JDBC_URL") ?: return
            registry.add("spring.datasource.url") { url }
            registry.add("spring.datasource.username") { System.getenv("BIBLE_SEARCH_TEST_JDBC_USER") ?: "postgres" }
            registry.add("spring.datasource.password") { System.getenv("BIBLE_SEARCH_TEST_JDBC_PASSWORD") ?: "" }
            registry.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
            registry.add("spring.flyway.enabled") { "true" }
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
        }
    }
}
