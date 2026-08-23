package com.example.mypasture.testing

import com.example.mypasture.bible.infrastructure.BibleBookEntityRepository
import com.example.mypasture.bible.infrastructure.BibleVerseEntityRepository
import com.example.mypasture.daily_word.domain.DailyWordEntity
import com.example.mypasture.daily_word.domain.DailyWordSource
import com.example.mypasture.daily_word.domain.DailyWordStatus
import com.example.mypasture.daily_word.infrastructure.DailyWordRepository
import java.time.Instant
import java.time.LocalDate

/** 알림과 MVP 통합 테스트에서 원문과 연결된 게시본을 일관되게 생성합니다. */
object DailyWordFixtures {
    fun published(
        date: LocalDate,
        repository: DailyWordRepository,
        bookRepository: BibleBookEntityRepository,
        verseRepository: BibleVerseEntityRepository,
    ): DailyWordEntity {
        repository.findByWordDate(date)?.let { return it }
        val book = bookRepository.findByVersionAndCode("KOR1910", "GEN") ?: error("GEN book fixture is missing")
        val verse = verseRepository.findByVersionAndBook_IdAndChapterAndVerse("KOR1910", book.id, 1, 1)
            ?: error("GEN 1:1 fixture is missing")
        val now = Instant.parse("2026-08-01T00:00:00Z")
        return repository.saveAndFlush(
            DailyWordEntity(
                wordDate = date,
                verseId = verse.id,
                bibleVersion = verse.version,
                bookCode = book.code,
                bookName = book.name,
                chapter = verse.chapter,
                verse = verse.verse,
                verseText = verse.text,
                meditation = "하나님이 시작하신 창조의 질서와 선하심을 바라보며 오늘의 선택을 천천히 돌아봅니다.",
                actionQuestion = "오늘 하나님의 선하심을 드러낼 수 있는 작은 실천은 무엇인가요?",
                published = true,
                status = DailyWordStatus.PUBLISHED,
                source = DailyWordSource.CURATED,
                generatedAt = now,
                reviewedAt = now,
                publishedAt = now,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }
}
