package com.example.mypasture.daily_word.application

import com.example.mypasture.daily_word.domain.DailyWordTopic
import com.example.mypasture.daily_word.domain.DailyWordPassageType
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
    fun `확장한 연속 읽기 목록은 기존 앞부분을 보존하고 확인된 창세기 장을 정확히 한 번 포함한다`() {
        val originalRanges = listOf(
            Triple(1, 1, 5), Triple(1, 6, 8), Triple(1, 9, 13), Triple(1, 14, 19), Triple(1, 20, 23), Triple(1, 24, 31),
            Triple(2, 1, 3), Triple(2, 4, 9), Triple(2, 10, 14), Triple(2, 15, 17), Triple(2, 18, 25),
            Triple(3, 1, 7), Triple(3, 8, 13), Triple(3, 14, 19), Triple(3, 20, 24),
            Triple(4, 1, 7), Triple(4, 8, 16), Triple(4, 17, 24), Triple(4, 25, 26),
            Triple(5, 1, 5), Triple(5, 6, 11), Triple(5, 12, 17), Triple(5, 18, 24), Triple(5, 25, 32),
        )
        val continuous = catalog.continuousEntries
        assertEquals(originalRanges, continuous.take(24).map { Triple(it.chapter, it.verse, it.endVerse) })
        assertEquals(originalRanges.map { (chapter, start, _) -> "reading-GEN-$chapter-$start" }, continuous.take(24).map { it.id })
        assertEquals(originalRanges.map { (chapter, _, _) ->
            if (chapter == 5) DailyWordPassageType.GENEALOGY else DailyWordPassageType.NARRATIVE
        }, continuous.take(24).map { it.passageType })
        assertTrue(continuous.all { it.bookCode == "GEN" && it.topics.isEmpty() })
        assertTrue(continuous.all { it.verse >= 1 && it.endVerse >= it.verse && it.endVerse - it.verse + 1 <= 20 })
        assertEquals(continuous.size, continuous.map { it.id }.distinct().size)
        assertEquals((1..50).toList(), continuous.map { it.chapter }.distinct())
        for ((offset, count) in VERIFIED_GENESIS_COUNTS.withIndex()) {
            val chapter = offset + 1
            assertEquals((1..count).toList(), continuous.filter { it.chapter == chapter }.flatMap { (it.verse..it.endVerse).toList() },
                "GEN $chapter coverage must be ordered, complete and without duplicate verses")
        }
    }

    @Test
    fun `검증한 창세기 목록은 실제 원본의 모든 절을 누락과 중복 없이 포함한다`() {
        val directory = Path.of("data/kor_html")
        val chapterCount = if (Files.isDirectory(directory)) Files.list(directory).use { files ->
            files.filter { Regex("[1-3]?[A-Z]{2,3}\\d+\\.htm").matches(it.fileName.toString()) }.count()
        } else 0L
        assumeTrue(chapterCount >= 1000, "Complete external KOR1910 corpus unavailable ($chapterCount chapter files); fixed Genesis coverage test still runs")
        for ((offset, count) in VERIFIED_GENESIS_COUNTS.withIndex()) {
            val chapter = offset + 1
            val file = listOf(2, 3).map { directory.resolve("GEN${chapter.toString().padStart(it, '0')}.htm") }
                .firstOrNull(Files::isRegularFile) ?: error("Actual chapter missing: GEN $chapter")
            val actual = Regex("""<span\b[^>]*\bid=["']V(\d+)["'][^>]*>.*?</span>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(Files.readString(file)).map { paragraph ->
                    paragraph.groupValues[1].toInt() to paragraph.groupValues[2]
                        .replace(Regex("<[^>]+>"), "").replace("&nbsp;", " ").replace("&#160;", " ")
                        .replace("&#xA0;", " ").replace('\u00a0', ' ').trim()
                }.filter { it.first > 0 }.toList()
            assertEquals((1..count).toList(), actual.map { it.first }, "GEN $chapter actual source must be complete and unique")
            assertTrue(actual.all { it.second.isNotBlank() }, "GEN $chapter contains blank source verse")
            assertEquals(actual.map { it.first }, catalog.continuousEntries.filter { it.chapter == chapter }
                .flatMap { (it.verse..it.endVerse).toList() }, "GEN $chapter source/catalog coverage differs")
        }
    }

    @Test
    fun `명시한 모든 범위는 실제 한글 원본 파일에 완전히 존재한다`() {
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
            entry.topics.forEach { topic ->
                assertEquals(entry.verse, entry.endVerse, entry.id)
                assertTrue(topic.appearsIn(source.getValue(entry.verse)), "${entry.id} does not express ${topic.label} in its own verse")
            }
        }
    }

    @Test
    fun `매일 기존 주제의 한 절만 사용하고 이전 읽기 목록도 유지한다`() {
        val monday = LocalDate.of(2026, 10, 5)
        for ((offset, topic) in DailyWordTopic.entries.withIndex()) {
            val entries = catalog.themedEntries.getValue(topic)
            assertTrue(entries.size >= 10, topic.name)
            assertTrue(entries.all { topic in it.topics && it.verse == it.endVerse }, topic.name)
            assertEquals(topic, catalog.topic(monday.plusDays(offset.toLong())))
        }
        assertTrue(catalog.continuousEntries.size >= 20)
        assertTrue(catalog.continuousEntries.all { it.topics.isEmpty() })
        (0L..13L).forEach { day ->
            val date = monday.plusDays(day)
            assertTrue(catalog.candidates(date).all { it.topics == setOf(catalog.topic(date)) && it.verse == it.endVerse })
        }
    }

    @Test
    fun `주말 날짜는 미분류 구절 대신 기존 주제를 순환한다`() {
        val dates = listOf(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 11), LocalDate.of(2026, 10, 17))
        assertEquals(3, dates.map(catalog::topic).distinct().size)
        dates.forEach { date ->
            assertEquals(catalog.topic(date), catalog.candidates(date).first().topics.single())
        }
    }

    @Test
    fun `각 요일은 모든 항목을 한 번씩 쓴 뒤 반복하고 날짜에 따라 결과가 일정하다`() {
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

    companion object {
        // 기존 1~29장 순서를 유지하면서 보강한 원문 기준으로 창세기 전체를 검증합니다.
        private val VERIFIED_GENESIS_COUNTS = listOf(
            31, 25, 24, 26, 32, 22, 24, 22, 29, 32, 32, 20, 18, 24, 21,
            16, 27, 33, 38, 18, 34, 24, 20, 67, 34, 35, 46, 22, 35, 43,
            55, 32, 20, 31, 29, 43, 36, 30, 23, 23, 57, 38, 34, 34, 28,
            34, 31, 22, 33, 26,
        )
    }
}
