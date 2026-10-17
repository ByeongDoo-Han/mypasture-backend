package com.example.mypasture.quiet_time.application

import com.example.mypasture.bible.application.BibleReaderService
import com.example.mypasture.quiet_time.domain.QuietTimePassageEntity
import com.example.mypasture.quiet_time.infrastructure.QuietTimePassageRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** 목자가 선택한 실제 성경 절 범위를 검증하고 목장 간 공용 QT 본문으로 등록합니다. */
@Service
class QuietTimeSelectedPassageService(
    private val bibleReader: BibleReaderService,
    private val passageRepository: QuietTimePassageRepository,
    private val jdbc: JdbcTemplate,
) {
    @Transactional
    fun select(version: String, bookCode: String, chapter: Int, startVerse: Int, endVerse: Int): QuietTimePassageEntity {
        require(startVerse > 0 && endVerse >= startVerse && endVerse - startVerse < 10) {
            "QT 본문은 같은 장에서 연속된 1~10절로 선택해 주세요."
        }
        val bibleChapter = bibleReader.getChapter(version, bookCode, chapter)
        val selected = bibleChapter.verses.filter { it.verse in startVerse..endVerse }
        require(selected.size == endVerse - startVerse + 1) { "선택한 절이 성경 본문에 없습니다." }
        val normalizedVersion = bibleChapter.version
        val book = bibleChapter.book
        val reference = "$chapter:$startVerse${if (endVerse != startVerse) "-$endVerse" else ""}"
        val key = "$normalizedVersion:${book.code}:$chapter:$startVerse:$endVerse"
        val id = UUID.randomUUID()
        jdbc.update(
            """
            insert into quiet_time_passages(
              id, title, bible_version, book_code, book_name, start_chapter, start_verse,
              end_chapter, end_verse, theme_tags, readability_score, context_score,
              sharing_score, application_score, beginner_score, sensitivity_level,
              recommended_group_size, estimated_minutes, observation_questions,
              meditation_questions, sharing_questions, action_guide, prayer_guide, active, created_at, origin, selected_key
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 3, 3, 3, 3, 3, 'LOW',
              'SMALL_GROUP', 15, '', '', '', '', '', true, ?, 'USER_SELECTED', ?)
            on conflict do nothing
            """.trimIndent(),
            id, "${book.name} $reference", normalizedVersion, book.code, book.name, chapter,
            startVerse, chapter, endVerse, "말씀 묵상", java.sql.Timestamp.from(java.time.Instant.now()), key,
        )
        val selectedId = jdbc.queryForObject(
            """
            select id from quiet_time_passages where selected_key = ?
            """.trimIndent(),
            UUID::class.java,
            key,
        ) ?: error("선택한 QT 본문을 찾을 수 없습니다.")
        return passageRepository.findById(selectedId).orElseThrow()
    }
}
