package com.example.mypasture.daily_word.presentation

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class DailyWordResponse(
    val id: UUID, val date: LocalDate, val version: String, val bookCode: String, val bookName: String,
    val chapter: Int, val verse: Int, val verseText: String, val meditation: String, val actionQuestion: String,
    val publishedAt: Instant?, val endVerse: Int? = null, val passageText: String? = null,
    val topics: List<String> = emptyList(), val readingMode: String? = null, val passageType: String? = null,
)

/** 오늘의 말씀과 공통 묵상 콘텐츠 공개 API입니다. */
interface DailyWordApi {
    @GetMapping fun today(@RequestParam(required = false) date: LocalDate?): DailyWordResponse
}
