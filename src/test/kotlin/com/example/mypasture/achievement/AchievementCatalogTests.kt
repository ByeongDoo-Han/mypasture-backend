package com.example.mypasture.achievement

import com.example.mypasture.achievement.domain.*
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals

/** 날짜·정규 장 범위·임계치의 순수 판정 규칙을 검증합니다. */
class AchievementCatalogTests {
    @Test fun `업적 목록에는 6개 분야의 서로 다른 업적 12개가 있다`() {
        assertEquals(12, AchievementCatalog.definitions.map { it.code }.distinct().size)
        assertEquals(6, AchievementCatalog.definitions.map { it.category }.distinct().size)
    }

    @Test fun `읽은 날짜와 최장 연속 기록은 연도와 윤일 경계에서도 중복 날짜를 제거한다`() {
        val dates = (0L..6L).map { LocalDate.of(2023, 12, 28).plusDays(it) }.toSet()
        val evidence = AchievementEvidence(readingDays = dates + LocalDate.of(2024, 3, 10))
        assertEquals(8, evidence.progress(AchievementCategory.READING_DAYS))
        assertEquals(7, evidence.progress(AchievementCategory.READING_STREAK))
        val leap = AchievementEvidence(readingDays = (0L..6L).map { LocalDate.of(2024, 2, 26).plusDays(it) }.toSet())
        assertEquals(7, leap.progress(AchievementCategory.READING_STREAK))
    }

    @Test fun `하루를 빠뜨리면 연속 기록이 끊기지만 과거 업적 달성 가능성은 유지된다`() {
        val start = LocalDate.of(2024, 1, 1)
        val evidence = AchievementEvidence(readingDays = (0L..7L).filter { it != 3L }.map(start::plusDays).toSet())
        assertEquals(4, evidence.progress(AchievementCategory.READING_STREAK))
    }

    @Test fun `정규 성경과 서로 다른 모든 장을 읽어야 완독이 성립한다`() {
        val almost = (1..49).map { "GEN" to it }.toSet() + setOf("GEN" to 51, "UNKNOWN" to 1, "OBA" to 0)
        assertEquals(0, AchievementEvidence(completedChapters = almost).progress(AchievementCategory.BOOK_COMPLETION))
        assertEquals(1, AchievementEvidence(completedChapters = almost + ("GEN" to 50)).progress(AchievementCategory.BOOK_COMPLETION))
        assertEquals(1, AchievementEvidence(completedChapters = setOf("OBA" to 1)).progress(AchievementCategory.BOOK_COMPLETION))
        assertEquals(0, AchievementEvidence(completedChapters = setOf("UNKNOWN" to 1)).progress(AchievementCategory.FIRST_READING))
    }

    @Test fun `누적 단계별로 필요한 서로 다른 날짜 또는 북마크 수를 모두 채워야 한다`() {
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
