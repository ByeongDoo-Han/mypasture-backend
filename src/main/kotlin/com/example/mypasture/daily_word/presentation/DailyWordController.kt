package com.example.mypasture.daily_word.presentation

import com.example.mypasture.daily_word.application.DailyWordService
import com.example.mypasture.daily_word.domain.DailyWordEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/** 모바일 홈에 오늘의 말씀과 묵상을 제공합니다. */
@RestController
@RequestMapping("/api/v1/today-word")
class DailyWordController(private val service: DailyWordService) : DailyWordApi {
    override fun today(date: LocalDate?) = (date?.let(service::getPublished) ?: service.getPublished()).toResponse()
}
private fun DailyWordEntity.toResponse() = DailyWordResponse(
    id, wordDate, bibleVersion, bookCode, bookName, chapter, verse, verseText, meditation, actionQuestion, publishedAt,
    endVerse, passageText, topics, readingMode?.name, passageType?.name,
)
