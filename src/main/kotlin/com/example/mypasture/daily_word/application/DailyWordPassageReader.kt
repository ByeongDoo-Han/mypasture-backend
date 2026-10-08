package com.example.mypasture.daily_word.application

import com.example.mypasture.bible.domain.BibleVerseEntity
import com.example.mypasture.bible.infrastructure.BibleBookEntityRepository
import com.example.mypasture.bible.infrastructure.BibleVerseEntityRepository
import com.example.mypasture.daily_word.domain.DailyWordEntity
import com.example.mypasture.daily_word.domain.DailyWordRotationEntry
import org.springframework.stereotype.Component

/** 원문 전체 범위를 DB와 대조해 누락된 절이나 빈 본문의 자동 게시를 막습니다. */
@Component
class DailyWordPassageReader(
    private val books: BibleBookEntityRepository,
    private val verses: BibleVerseEntityRepository,
) {
    fun read(entry: DailyWordRotationEntry): VerifiedDailyWordPassage? =
        read("KOR1910", entry.bookCode, entry.chapter, entry.verse, entry.endVerse)

    private fun read(version: String, bookCode: String, chapter: Int, start: Int, end: Int): VerifiedDailyWordPassage? {
        if (start < 1 || end < start) return null
        val book = books.findByVersionAndCode(version, bookCode) ?: return null
        val found = verses.findAllByVersionAndBook_IdAndChapterOrderByVerse(version, book.id, chapter)
            .filter { it.verse in start..end }
        if (found.map { it.verse } != (start..end).toList() || found.any { it.text.isBlank() }) return null
        return VerifiedDailyWordPassage(found.first(), format(found))
    }

    fun verify(word: DailyWordEntity) {
        val source = read(word.bibleVersion, word.bookCode, word.chapter, word.verse, word.endVerse ?: word.verse)
            ?: throw DailyWordGenerationException("오늘의 말씀 본문 범위를 현재 성경에서 찾을 수 없습니다.")
        if (source.first.id != word.verseId || source.first.text != word.verseText ||
            (word.passageText != null && word.passageText != source.text) ||
            ((word.endVerse ?: word.verse) > word.verse && word.passageText == null)
        ) throw DailyWordGenerationException("오늘의 말씀 본문이 현재 성경 원문과 일치하지 않습니다.")
    }

    private fun format(found: List<BibleVerseEntity>) =
        if (found.size == 1) found.first().text else found.joinToString("\n") { "${it.verse}. ${it.text}" }
}

/** 기존 첫 절 계약과 여러 절 전체 본문을 함께 전달하는 검증 결과입니다. */
data class VerifiedDailyWordPassage(val first: BibleVerseEntity, val text: String)
