package com.example.mypasture.daily_word.application

import com.example.mypasture.daily_word.domain.*
import org.springframework.stereotype.Component
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 새 게시에는 주제별 단일 절을, 과거 연속 읽기 안내에는 기존 본문 목록을 제공합니다. */
@Component
class DailyWordRotationCatalog {
    val themedEntries: Map<DailyWordTopic, List<DailyWordRotationEntry>> = mapOf(
        DailyWordTopic.HOPE to listOf(
            themedVerse(DailyWordTopic.HOPE, "PSA", 42, 11, DailyWordPassageType.POETRY),
            themedVerse(DailyWordTopic.HOPE, "PSA", 62, 5, DailyWordPassageType.POETRY),
            themedVerse(DailyWordTopic.HOPE, "PSA", 71, 5, DailyWordPassageType.POETRY),
            themedVerse(DailyWordTopic.HOPE, "PSA", 130, 5, DailyWordPassageType.POETRY),
            themedVerse(DailyWordTopic.HOPE, "PSA", 39, 7, DailyWordPassageType.POETRY),
            themedVerse(DailyWordTopic.HOPE, "PSA", 31, 24, DailyWordPassageType.POETRY),
            themedVerse(DailyWordTopic.HOPE, "ROM", 8, 24, DailyWordPassageType.TEACHING),
            themedVerse(DailyWordTopic.HOPE, "ROM", 15, 13, DailyWordPassageType.TEACHING),
            themedVerse(DailyWordTopic.HOPE, "1PE", 1, 3, DailyWordPassageType.TEACHING),
            themedVerse(DailyWordTopic.HOPE, "ISA", 40, 31, DailyWordPassageType.TEACHING),
            themedVerse(DailyWordTopic.HOPE, "PRO", 23, 18, DailyWordPassageType.TEACHING),
            themedVerse(DailyWordTopic.HOPE, "LAM", 3, 24, DailyWordPassageType.TEACHING),
        ),
        DailyWordTopic.GRATITUDE to listOf(
            themedVerse(DailyWordTopic.GRATITUDE, "PSA", 107, 1, DailyWordPassageType.POETRY),
            themedVerse(DailyWordTopic.GRATITUDE, "PSA", 118, 1, DailyWordPassageType.POETRY),
            themedVerse(DailyWordTopic.GRATITUDE, "PSA", 136, 1, DailyWordPassageType.POETRY),
            themedVerse(DailyWordTopic.GRATITUDE, "1CH", 16, 8, DailyWordPassageType.TEACHING),
            themedVerse(DailyWordTopic.GRATITUDE, "COL", 3, 15, DailyWordPassageType.TEACHING),
            themedVerse(DailyWordTopic.GRATITUDE, "COL", 3, 17, DailyWordPassageType.TEACHING),
            themedVerse(DailyWordTopic.GRATITUDE, "1TH", 5, 18, DailyWordPassageType.TEACHING),
            themedVerse(DailyWordTopic.GRATITUDE, "PHP", 4, 6, DailyWordPassageType.TEACHING),
            themedVerse(DailyWordTopic.GRATITUDE, "1CO", 15, 57, DailyWordPassageType.TEACHING),
            themedVerse(DailyWordTopic.GRATITUDE, "PSA", 9, 1, DailyWordPassageType.POETRY),
            themedVerse(DailyWordTopic.GRATITUDE, "PSA", 100, 4, DailyWordPassageType.POETRY),
            themedVerse(DailyWordTopic.GRATITUDE, "EPH", 5, 20, DailyWordPassageType.TEACHING),
        ),
        DailyWordTopic.LOVE to listOf(
            themedVerse(DailyWordTopic.LOVE, "JHN", 3, 16, DailyWordPassageType.TEACHING),
            themedVerse(DailyWordTopic.LOVE, "JHN", 13, 34, DailyWordPassageType.TEACHING),
            themedVerse(DailyWordTopic.LOVE, "JHN", 15, 9, DailyWordPassageType.TEACHING),
            themedVerse(DailyWordTopic.LOVE, "1CO", 13, 4, DailyWordPassageType.TEACHING),
            themedVerse(DailyWordTopic.LOVE, "1CO", 13, 13, DailyWordPassageType.TEACHING),
            themedVerse(DailyWordTopic.LOVE, "ROM", 12, 9, DailyWordPassageType.TEACHING),
            themedVerse(DailyWordTopic.LOVE, "ROM", 13, 8, DailyWordPassageType.TEACHING),
            themedVerse(DailyWordTopic.LOVE, "1JN", 3, 16, DailyWordPassageType.TEACHING),
            themedVerse(DailyWordTopic.LOVE, "1JN", 4, 7, DailyWordPassageType.TEACHING),
            themedVerse(DailyWordTopic.LOVE, "1JN", 4, 19, DailyWordPassageType.TEACHING),
            themedVerse(DailyWordTopic.LOVE, "GAL", 5, 13, DailyWordPassageType.TEACHING),
            themedVerse(DailyWordTopic.LOVE, "COL", 3, 14, DailyWordPassageType.TEACHING),
        ),
        DailyWordTopic.PEACE to listOf(
            themedVerse(DailyWordTopic.PEACE, "ISA", 26, 3, DailyWordPassageType.TEACHING),
            themedVerse(DailyWordTopic.PEACE, "JHN", 14, 27, DailyWordPassageType.TEACHING),
            themedVerse(DailyWordTopic.PEACE, "JHN", 16, 33, DailyWordPassageType.TEACHING),
            themedVerse(DailyWordTopic.PEACE, "2TH", 3, 16, DailyWordPassageType.TEACHING),
            themedVerse(DailyWordTopic.PEACE, "PHP", 4, 7, DailyWordPassageType.TEACHING),
            themedVerse(DailyWordTopic.PEACE, "ROM", 5, 1, DailyWordPassageType.TEACHING),
            themedVerse(DailyWordTopic.PEACE, "ROM", 15, 13, DailyWordPassageType.TEACHING),
            themedVerse(DailyWordTopic.PEACE, "COL", 3, 15, DailyWordPassageType.TEACHING),
            themedVerse(DailyWordTopic.PEACE, "NUM", 6, 26, DailyWordPassageType.TEACHING),
            themedVerse(DailyWordTopic.PEACE, "PSA", 4, 8, DailyWordPassageType.POETRY),
            themedVerse(DailyWordTopic.PEACE, "PSA", 29, 11, DailyWordPassageType.POETRY),
            themedVerse(DailyWordTopic.PEACE, "PSA", 119, 165, DailyWordPassageType.POETRY),
        ),
        DailyWordTopic.JOY to listOf(
            themedVerse(DailyWordTopic.JOY, "PSA", 95, 1, DailyWordPassageType.POETRY),
            themedVerse(DailyWordTopic.JOY, "PSA", 98, 4, DailyWordPassageType.POETRY),
            themedVerse(DailyWordTopic.JOY, "ROM", 14, 17, DailyWordPassageType.TEACHING),
            themedVerse(DailyWordTopic.JOY, "PHP", 4, 4, DailyWordPassageType.TEACHING),
            themedVerse(DailyWordTopic.JOY, "1TH", 5, 16, DailyWordPassageType.TEACHING),
            themedVerse(DailyWordTopic.JOY, "JAS", 1, 2, DailyWordPassageType.TEACHING),
            themedVerse(DailyWordTopic.JOY, "PSA", 30, 5, DailyWordPassageType.POETRY),
            themedVerse(DailyWordTopic.JOY, "PSA", 32, 11, DailyWordPassageType.POETRY),
            themedVerse(DailyWordTopic.JOY, "PSA", 126, 3, DailyWordPassageType.POETRY),
            themedVerse(DailyWordTopic.JOY, "HAB", 3, 18, DailyWordPassageType.TEACHING),
            themedVerse(DailyWordTopic.JOY, "JHN", 15, 11, DailyWordPassageType.TEACHING),
            themedVerse(DailyWordTopic.JOY, "PSA", 100, 2, DailyWordPassageType.POETRY),
        ),
    )
    val continuousEntries: List<DailyWordRotationEntry> = listOf(
        entry("reading-GEN-1-1", "GEN", 1, 1, 5, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-1-6", "GEN", 1, 6, 8, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-1-9", "GEN", 1, 9, 13, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-1-14", "GEN", 1, 14, 19, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-1-20", "GEN", 1, 20, 23, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-1-24", "GEN", 1, 24, 31, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-2-1", "GEN", 2, 1, 3, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-2-4", "GEN", 2, 4, 9, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-2-10", "GEN", 2, 10, 14, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-2-15", "GEN", 2, 15, 17, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-2-18", "GEN", 2, 18, 25, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-3-1", "GEN", 3, 1, 7, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-3-8", "GEN", 3, 8, 13, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-3-14", "GEN", 3, 14, 19, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-3-20", "GEN", 3, 20, 24, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-4-1", "GEN", 4, 1, 7, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-4-8", "GEN", 4, 8, 16, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-4-17", "GEN", 4, 17, 24, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-4-25", "GEN", 4, 25, 26, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-5-1", "GEN", 5, 1, 5, emptySet(), DailyWordPassageType.GENEALOGY),
        entry("reading-GEN-5-6", "GEN", 5, 6, 11, emptySet(), DailyWordPassageType.GENEALOGY),
        entry("reading-GEN-5-12", "GEN", 5, 12, 17, emptySet(), DailyWordPassageType.GENEALOGY),
        entry("reading-GEN-5-18", "GEN", 5, 18, 24, emptySet(), DailyWordPassageType.GENEALOGY),
        entry("reading-GEN-5-25", "GEN", 5, 25, 32, emptySet(), DailyWordPassageType.GENEALOGY),
        entry("reading-GEN-6-1", "GEN", 6, 1, 8, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-6-9", "GEN", 6, 9, 22, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-7-1", "GEN", 7, 1, 16, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-7-17", "GEN", 7, 17, 24, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-8-1", "GEN", 8, 1, 14, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-8-15", "GEN", 8, 15, 22, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-9-1", "GEN", 9, 1, 7, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-9-8", "GEN", 9, 8, 17, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-9-18", "GEN", 9, 18, 29, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-10-1", "GEN", 10, 1, 5, emptySet(), DailyWordPassageType.GENEALOGY),
        entry("reading-GEN-10-6", "GEN", 10, 6, 20, emptySet(), DailyWordPassageType.GENEALOGY),
        entry("reading-GEN-10-21", "GEN", 10, 21, 32, emptySet(), DailyWordPassageType.GENEALOGY),
        entry("reading-GEN-11-1", "GEN", 11, 1, 9, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-11-10", "GEN", 11, 10, 26, emptySet(), DailyWordPassageType.GENEALOGY),
        entry("reading-GEN-11-27", "GEN", 11, 27, 32, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-12-1", "GEN", 12, 1, 9, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-12-10", "GEN", 12, 10, 20, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-13-1", "GEN", 13, 1, 13, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-13-14", "GEN", 13, 14, 18, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-14-1", "GEN", 14, 1, 12, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-14-13", "GEN", 14, 13, 16, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-14-17", "GEN", 14, 17, 24, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-15-1", "GEN", 15, 1, 6, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-15-7", "GEN", 15, 7, 21, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-16-1", "GEN", 16, 1, 6, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-16-7", "GEN", 16, 7, 16, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-17-1", "GEN", 17, 1, 14, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-17-15", "GEN", 17, 15, 27, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-18-1", "GEN", 18, 1, 15, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-18-16", "GEN", 18, 16, 33, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-19-1", "GEN", 19, 1, 14, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-19-15", "GEN", 19, 15, 29, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-19-30", "GEN", 19, 30, 38, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-20-1", "GEN", 20, 1, 18, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-21-1", "GEN", 21, 1, 7, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-21-8", "GEN", 21, 8, 21, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-21-22", "GEN", 21, 22, 34, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-22-1", "GEN", 22, 1, 19, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-22-20", "GEN", 22, 20, 24, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-23-1", "GEN", 23, 1, 20, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-24-1", "GEN", 24, 1, 14, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-24-15", "GEN", 24, 15, 27, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-24-28", "GEN", 24, 28, 33, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-24-34", "GEN", 24, 34, 49, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-24-50", "GEN", 24, 50, 67, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-25-1", "GEN", 25, 1, 11, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-25-12", "GEN", 25, 12, 18, emptySet(), DailyWordPassageType.GENEALOGY),
        entry("reading-GEN-25-19", "GEN", 25, 19, 26, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-25-27", "GEN", 25, 27, 34, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-26-1", "GEN", 26, 1, 11, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-26-12", "GEN", 26, 12, 22, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-26-23", "GEN", 26, 23, 35, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-27-1", "GEN", 27, 1, 17, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-27-18", "GEN", 27, 18, 29, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-27-30", "GEN", 27, 30, 40, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-27-41", "GEN", 27, 41, 46, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-28-1", "GEN", 28, 1, 9, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-28-10", "GEN", 28, 10, 22, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-29-1", "GEN", 29, 1, 14, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-29-15", "GEN", 29, 15, 30, emptySet(), DailyWordPassageType.NARRATIVE),
        entry("reading-GEN-29-31", "GEN", 29, 31, 35, emptySet(), DailyWordPassageType.NARRATIVE),
    ) + generatedGenesisEntries(30..50)
    val entries: List<DailyWordRotationEntry> = themedEntries.values.flatten() + continuousEntries

    /** 게시본의 고정된 ID를 기준으로 본문 순서만 안내하며 개인 완독 여부는 판단하지 않습니다. */
    fun guide(entryId: String?): DailyWordReadingGuide? {
        if (entryId == null) return null
        val index = continuousEntries.indexOfFirst { it.id == entryId }
        if (index < 0) return null
        return DailyWordReadingGuide(
            current = continuousEntries[index], position = index + 1, total = continuousEntries.size,
            previous = continuousEntries.getOrNull(index - 1), next = continuousEntries.getOrNull(index + 1),
        )
    }

    fun topic(date: LocalDate): DailyWordTopic = when (date.dayOfWeek) {
        DayOfWeek.MONDAY -> DailyWordTopic.HOPE
        DayOfWeek.TUESDAY -> DailyWordTopic.GRATITUDE
        DayOfWeek.WEDNESDAY -> DailyWordTopic.LOVE
        DayOfWeek.THURSDAY -> DailyWordTopic.PEACE
        DayOfWeek.FRIDAY -> DailyWordTopic.JOY
        else -> DailyWordTopic.entries[Math.floorMod(date.toEpochDay(), DailyWordTopic.entries.size.toLong()).toInt()]
    }

    /** 후보 오류 시 같은 목록의 다음 항목으로 이동하며 모든 후보의 실패는 게시를 중단합니다. */
    fun candidates(date: LocalDate): List<DailyWordRotationEntry> {
        val pool = themedEntries.getValue(topic(date))
        val week = Math.floorDiv(ChronoUnit.DAYS.between(ANCHOR, date), 7L)
        val index = Math.floorMod(week, pool.size.toLong()).toInt()
        return pool.drop(index) + pool.take(index)
    }

    private fun themedVerse(topic: DailyWordTopic, book: String, chapter: Int, verse: Int, type: DailyWordPassageType) =
        entry("${topic.name.lowercase()}-$book-$chapter-$verse", book, chapter, verse, verse, setOf(topic), type)

    private fun entry(id: String, book: String, chapter: Int, verse: Int, end: Int, topics: Set<DailyWordTopic>, type: DailyWordPassageType) =
        DailyWordRotationEntry(id, book, chapter, verse, end, topics, type)

    /** 검증이 끝난 나머지 창세기를 20절 이하의 순차 범위로 확장합니다. */
    private fun generatedGenesisEntries(chapters: IntRange): List<DailyWordRotationEntry> = chapters.flatMap { chapter ->
        val passageType = if (chapter in GENESIS_GENEALOGY_CHAPTERS) {
            DailyWordPassageType.GENEALOGY
        } else {
            DailyWordPassageType.NARRATIVE
        }
        (1..GENESIS_VERSE_COUNTS[chapter]).chunked(MAX_CONTINUOUS_PASSAGE_VERSES).map { verses ->
            entry(
                "reading-GEN-$chapter-${verses.first()}",
                "GEN",
                chapter,
                verses.first(),
                verses.last(),
                emptySet(),
                passageType,
            )
        }
    }

    companion object {
        val ANCHOR: LocalDate = LocalDate.of(2026, 10, 5)
        private const val MAX_CONTINUOUS_PASSAGE_VERSES = 20
        private val GENESIS_GENEALOGY_CHAPTERS = setOf(36, 46)
        private val GENESIS_VERSE_COUNTS = listOf(
            0,
            31, 25, 24, 26, 32, 22, 24, 22, 29, 32,
            32, 20, 18, 24, 21, 16, 27, 33, 38, 18,
            34, 24, 20, 67, 34, 35, 46, 22, 35, 43,
            55, 32, 20, 31, 29, 43, 36, 30, 23, 23,
            57, 38, 34, 34, 28, 34, 31, 22, 33, 26,
        )
    }
}
