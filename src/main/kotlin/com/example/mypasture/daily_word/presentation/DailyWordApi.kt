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
    val readingGuide: DailyWordReadingGuideResponse? = null,
)

/** 공개 연속 읽기의 순서와 인접 본문이며 다음 날짜 배정이나 개인 완독을 의미하지 않습니다. */
data class DailyWordReadingGuideResponse(
    val position: Int, val total: Int,
    val previous: DailyWordReadingPassageResponse?, val next: DailyWordReadingPassageResponse?,
)

/** 성경 화면에서 열 수 있는 원문 범위의 공개 참조입니다. */
data class DailyWordReadingPassageResponse(
    val version: String, val bookCode: String, val bookName: String,
    val chapter: Int, val verse: Int, val endVerse: Int,
)

/** 오늘의 말씀과 공통 묵상 콘텐츠 공개 API입니다. */
interface DailyWordApi {
    @GetMapping fun today(@RequestParam(required = false) date: LocalDate?): DailyWordResponse
}
