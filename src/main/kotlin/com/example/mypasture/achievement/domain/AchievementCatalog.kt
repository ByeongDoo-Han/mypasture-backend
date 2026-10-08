package com.example.mypasture.achievement.domain

import com.example.mypasture.bible.domain.BibleCatalog
import java.time.LocalDate

/** 서버가 판정하는 업적 종류이며 클라이언트의 달성 수치를 신뢰하지 않습니다. */
enum class AchievementCategory { FIRST_READING, READING_DAYS, READING_STREAK, BOOK_COMPLETION, BOOKMARKS, NOTE_DAYS }

/** 문구·목표·단위를 한곳에서 관리하는 고정 업적 정의입니다. */
data class AchievementDefinition(val code: String, val category: AchievementCategory, val title: String, val description: String, val target: Int, val unit: String)

/** 본문과 메모 내용을 포함하지 않는 사용자별 판정 근거입니다. */
data class AchievementEvidence(
    val readingDays: Set<LocalDate> = emptySet(),
    val completedChapters: Set<Pair<String, Int>> = emptySet(),
    val bookmarkCount: Int = 0,
    val noteDays: Set<LocalDate> = emptySet(),
) {
    fun progress(category: AchievementCategory): Int = when (category) {
        AchievementCategory.FIRST_READING -> if (validChapters().isEmpty()) 0 else 1
        AchievementCategory.READING_DAYS -> readingDays.size
        AchievementCategory.READING_STREAK -> longestStreak(readingDays)
        AchievementCategory.BOOK_COMPLETION -> {
            val chapters = validChapters().groupBy({ it.first }, { it.second })
            BibleCatalog.books.count { book -> chapters[book.code]?.toSet()?.let { present -> (1..book.chapterCount).all { it in present } } == true }
        }
        AchievementCategory.BOOKMARKS -> bookmarkCount
        AchievementCategory.NOTE_DAYS -> noteDays.size
    }

    private fun validChapters() = completedChapters.filter { (code, chapter) ->
        BibleCatalog.books.any { it.code == code && chapter in 1..it.chapterCount }
    }

    private fun longestStreak(days: Set<LocalDate>): Int {
        var previous: LocalDate? = null
        var run = 0
        var longest = 0
        for (day in days.sorted()) {
            run = if (previous?.plusDays(1) == day) run + 1 else 1
            longest = maxOf(longest, run)
            previous = day
        }
        return longest
    }
}

/** 여섯 계열 열두 업적의 응답 순서와 규칙 버전을 고정합니다. */
object AchievementCatalog {
    const val RULE_VERSION = 1
    val definitions: List<AchievementDefinition> = buildList {
        add(AchievementDefinition("FIRST_CHAPTER", AchievementCategory.FIRST_READING, "첫 말씀의 발걸음", "성경 한 장을 처음 완독했어요.", 1, "장"))
        for (days in listOf(7, 30, 100, 365)) add(AchievementDefinition("READ_DAYS_$days", AchievementCategory.READING_DAYS, "말씀과 함께한 ${days}일", "성경을 한 장 이상 완독한 날이 ${days}일이에요.", days, "일"))
        add(AchievementDefinition("READ_STREAK_7", AchievementCategory.READING_STREAK, "일주일의 말씀 습관", "7일 연속으로 성경을 한 장 이상 완독했어요.", 7, "일"))
        add(AchievementDefinition("FIRST_BOOK", AchievementCategory.BOOK_COMPLETION, "첫 권 완독", "성경 한 권의 모든 장을 완독했어요.", 1, "권"))
        for (count in listOf(5, 20)) add(AchievementDefinition("BOOKMARKS_$count", AchievementCategory.BOOKMARKS, "간직한 말씀 ${count}절", "서로 다른 말씀 ${count}절을 북마크에 모았어요.", count, "절"))
        for (days in listOf(3, 10, 30)) add(AchievementDefinition("NOTE_DAYS_$days", AchievementCategory.NOTE_DAYS, "마음을 기록한 ${days}일", "서로 다른 ${days}일에 말씀 메모를 작성했어요.", days, "일"))
    }
}
