package com.example.mypasture.achievement

import com.example.mypasture.achievement.domain.*
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals

/** 날짜·정규 장 범위·임계치의 순수 판정 규칙을 검증합니다. */
class AchievementCatalogTests {
    @Test fun `catalog has twelve distinct achievements in six categories`() {
        assertEquals(12, AchievementCatalog.definitions.map { it.code }.distinct().size)
        assertEquals(6, AchievementCatalog.definitions.map { it.category }.distinct().size)
    }

    @Test fun `reading days and longest streak deduplicate dates across year and leap boundaries`() {
        val dates = (0L..6L).map { LocalDate.of(2023, 12, 28).plusDays(it) }.toSet()
        val evidence = AchievementEvidence(readingDays = dates + LocalDate.of(2024, 3, 10))
        assertEquals(8, evidence.progress(AchievementCategory.READING_DAYS))
        assertEquals(7, evidence.progress(AchievementCategory.READING_STREAK))
        val leap = AchievementEvidence(readingDays = (0L..6L).map { LocalDate.of(2024, 2, 26).plusDays(it) }.toSet())
        assertEquals(7, leap.progress(AchievementCategory.READING_STREAK))
    }

    @Test fun `a missing day breaks a streak but past achievement remains attainable`() {
        val start = LocalDate.of(2024, 1, 1)
        val evidence = AchievementEvidence(readingDays = (0L..7L).filter { it != 3L }.map(start::plusDays).toSet())
        assertEquals(4, evidence.progress(AchievementCategory.READING_STREAK))
    }

    @Test fun `only canonical books and every distinct chapter establish completion`() {
        val almost = (1..49).map { "GEN" to it }.toSet() + setOf("GEN" to 51, "UNKNOWN" to 1, "OBA" to 0)
        assertEquals(0, AchievementEvidence(completedChapters = almost).progress(AchievementCategory.BOOK_COMPLETION))
        assertEquals(1, AchievementEvidence(completedChapters = almost + ("GEN" to 50)).progress(AchievementCategory.BOOK_COMPLETION))
        assertEquals(1, AchievementEvidence(completedChapters = setOf("OBA" to 1)).progress(AchievementCategory.BOOK_COMPLETION))
        assertEquals(0, AchievementEvidence(completedChapters = setOf("UNKNOWN" to 1)).progress(AchievementCategory.FIRST_READING))
    }

    @Test fun `each accumulated threshold requires its full number of distinct days or bookmarks`() {
        AchievementCatalog.definitions.filter { it.category in setOf(AchievementCategory.READING_DAYS, AchievementCategory.NOTE_DAYS, AchievementCategory.BOOKMARKS) }.forEach { definition ->
            fun sample(size: Int) = AchievementEvidence(
                readingDays = (0 until size).map { LocalDate.of(2020, 1, 1).plusDays(it.toLong()) }.toSet(),
                noteDays = (0 until size).map { LocalDate.of(2020, 1, 1).plusDays(it.toLong()) }.toSet(), bookmarkCount = size,
            )
            assertEquals(definition.target - 1, sample(definition.target - 1).progress(definition.category))
            assertEquals(definition.target, sample(definition.target).progress(definition.category))
        }
    }
}
