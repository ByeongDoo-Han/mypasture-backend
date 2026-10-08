package com.example.mypasture.daily_word.presentation

import com.example.mypasture.bible.domain.BibleCatalog
import com.example.mypasture.daily_word.application.DailyWordRotationCatalog
import com.example.mypasture.daily_word.domain.DailyWordEntity
import com.example.mypasture.daily_word.domain.DailyWordReadingMode
import com.example.mypasture.daily_word.domain.DailyWordRotationEntry
import com.example.mypasture.daily_word.domain.DailyWordSource
import com.example.mypasture.daily_word.domain.DailyWordStatus
import org.springframework.stereotype.Component

/** 현재 카탈로그와 일치하는 연속 읽기 게시본만 공개 순서 안내로 변환합니다. */
@Component
class DailyWordReadingGuideMapper(private val catalog: DailyWordRotationCatalog) {
    fun from(word: DailyWordEntity): DailyWordReadingGuideResponse? {
        if (word.source != DailyWordSource.CURATED || word.status != DailyWordStatus.PUBLISHED ||
            word.readingMode != DailyWordReadingMode.CONTINUOUS || word.bibleVersion != BibleCatalog.KOR1910_VERSION) return null
        val guide = catalog.guide(word.rotationEntryId) ?: return null
        val current = guide.current
        if (current.bookCode != word.bookCode || current.chapter != word.chapter ||
            current.verse != word.verse || current.endVerse != word.endVerse) return null
        return DailyWordReadingGuideResponse(
            guide.position, guide.total, guide.previous?.toResponse(word.bibleVersion), guide.next?.toResponse(word.bibleVersion),
        )
    }

    private fun DailyWordRotationEntry.toResponse(version: String) = DailyWordReadingPassageResponse(
        version, bookCode, BibleCatalog.books.first { it.code == bookCode }.name, chapter, verse, endVerse,
    )
}
