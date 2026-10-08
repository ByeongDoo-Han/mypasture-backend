package com.example.mypasture.daily_word.application

import com.example.mypasture.daily_word.domain.DailyWordTopic
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/** 합성 DB fixture와 구분하여 모든 후보의 실제 KOR1910 원문과 날짜 순서를 검증합니다. */
class DailyWordRotationCatalogSourceTests {
    private val catalog = DailyWordRotationCatalog()

    @Test
    fun `every explicit range exists completely in actual Korean source files`() {
        val directory = Path.of("data/kor_html")
        val chapterCount = if (Files.isDirectory(directory)) Files.list(directory).use { files ->
            files.filter { Regex("[1-3]?[A-Z]{2,3}\\d+\\.htm").matches(it.fileName.toString()) }.count()
        } else 0L
        // CI의 소수 fixture를 전체 외부 corpus로 오인하지 않고 후보 누락은 아래에서 계속 거절합니다.
        assumeTrue(chapterCount >= 1000, "Complete external KOR1910 corpus unavailable ($chapterCount chapter files); synthetic/catalog logic tests remain separate")
        assertEquals(catalog.entries.size, catalog.entries.map { it.id }.distinct().size)
        val sourceByChapter = catalog.entries.groupBy { it.bookCode to it.chapter }.mapValues { (key, _) ->
            val (book, chapter) = key
            val file = listOf(2, 3).map { Path.of("data/kor_html", "$book${chapter.toString().padStart(it, '0')}.htm") }
                .firstOrNull(Files::isRegularFile) ?: error("Actual chapter missing: $book $chapter")
            // 각 verse marker 뒤부터 문단 끝까지 읽어 원문이 실제로 존재하는지 독립 확인합니다.
            Regex("""<span\b[^>]*\bid=["']V(\d+)["'][^>]*>.*?</span>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(Files.readString(file)).associate { paragraph ->
                    val text = paragraph.groupValues[2].replace(Regex("<[^>]+>"), "")
                        .replace("&nbsp;", " ").replace("&#160;", " ").replace("&#xA0;", " ")
                        .replace('\u00a0', ' ').trim()
                    paragraph.groupValues[1].toInt() to text
            }
        }
        for (entry in catalog.entries) {
            assertTrue(entry.verse > 0 && entry.endVerse >= entry.verse, entry.id)
            val source = sourceByChapter.getValue(entry.bookCode to entry.chapter)
            for (number in entry.verse..entry.endVerse) {
                assertFalse(source[number].isNullOrBlank(), "${entry.id}: ${entry.bookCode} ${entry.chapter}:$number")
            }
        }
    }

    @Test
    fun `weekday topics and weekend unclassified reading retain complete minimum catalogs`() {
        val monday = LocalDate.of(2026, 10, 5)
        for ((offset, topic) in DailyWordTopic.entries.withIndex()) {
            val entries = catalog.themedEntries.getValue(topic)
            assertTrue(entries.size >= 10, topic.name)
            assertTrue(entries.all { topic in it.topics }, topic.name)
            assertEquals(topic, catalog.topic(monday.plusDays(offset.toLong())))
        }
        assertTrue(catalog.continuousEntries.size >= 20)
        assertTrue(catalog.continuousEntries.all { it.topics.isEmpty() })
        assertNull(catalog.topic(monday.plusDays(5)))
        assertNull(catalog.topic(monday.plusDays(6)))
    }

    @Test
    fun `consecutive weekend dates advance context without resetting every week`() {
        val dates = listOf(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 11), LocalDate.of(2026, 10, 17))
        val references = dates.map { catalog.candidates(it).first() }
        assertEquals(listOf(1, 6, 9), references.map { it.verse })
        assertEquals(listOf(5, 8, 13), references.map { it.endVerse })
        assertTrue(references.all { it.bookCode == "GEN" && it.chapter == 1 })
        assertEquals(catalog.continuousEntries.last().id, catalog.candidates(LocalDate.of(2026, 10, 4)).first().id)
    }

    @Test
    fun `each weekday uses all its entries before repetition and remains date deterministic`() {
        val monday = LocalDate.of(2026, 10, 5)
        for ((offset, topic) in DailyWordTopic.entries.withIndex()) {
            val start = monday.plusDays(offset.toLong())
            val count = catalog.themedEntries.getValue(topic).size
            val cycle = (0 until count).map { catalog.candidates(start.plusWeeks(it.toLong())).first().id }
            assertEquals(count, cycle.distinct().size, topic.name)
            assertEquals(cycle.first(), catalog.candidates(start.plusWeeks(count.toLong())).first().id)
            assertEquals(catalog.themedEntries.getValue(topic).map { it.id }.toSet(), cycle.toSet())
        }
    }
}
