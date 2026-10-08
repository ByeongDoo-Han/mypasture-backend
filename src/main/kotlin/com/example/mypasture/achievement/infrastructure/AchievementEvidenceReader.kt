package com.example.mypasture.achievement.infrastructure

import com.example.mypasture.achievement.domain.AchievementEvidence
import com.example.mypasture.bible.domain.BibleCatalog
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** 사용자 콘텐츠 원문 대신 날짜와 위치만 읽어 업적 판정 근거를 집계합니다. */
@Repository
class AchievementEvidenceReader(private val jdbc: JdbcTemplate) {
    private val canonicalChapters = BibleCatalog.books.associate { it.code to it.chapterCount }

    fun read(userId: UUID): AchievementEvidence = AchievementEvidence(
        readingDays = jdbc.query(
            "select distinct book_code, chapter, read_on from bible_reading_history where user_id = ? and completed = true",
            { row, _ -> Triple(row.getString(1), row.getInt(2), row.getDate(3).toLocalDate()) }, userId,
        ).filter { (code, chapter, _) -> canonicalChapter(code, chapter) }.map { it.third }.toSet(),
        completedChapters = jdbc.query("select distinct book_code, chapter from bible_reading_states where user_id = ? and completed = true", { row, _ -> row.getString(1) to row.getInt(2) }, userId).filter { (code, chapter) -> canonicalChapter(code, chapter) }.toSet(),
        bookmarkCount = jdbc.queryForObject(
            """select count(*) from (
                select distinct book.code, verse.chapter, verse.verse
                from bookmarks bookmark
                join bible_verses verse on verse.id = bookmark.verse_id
                join bible_books book on book.id = verse.book_id
                where bookmark.user_id = ?
            ) locations""", Int::class.java, userId,
        ) ?: 0,
        noteDays = existingNoteDays(userId) + jdbc.query("select recorded_on from user_note_activity_days where user_id = ?", { row, _ -> row.getDate(1).toLocalDate() }, userId),
    )

    private fun canonicalChapter(code: String, chapter: Int) = canonicalChapters[code]?.let { chapter in 1..it } == true

    fun existingNoteDays(userId: UUID): Set<LocalDate> = jdbc.query(
        "select created_at from bible_notes where user_id = ?",
        { row, _ -> row.getTimestamp(1).toInstant().atZone(ZoneId.of("Asia/Seoul")).toLocalDate() }, userId,
    ).toSet()
}
