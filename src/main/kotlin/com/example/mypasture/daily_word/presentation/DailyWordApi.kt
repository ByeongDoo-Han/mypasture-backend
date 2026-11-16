package com.example.mypasture.daily_word.presentation

import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
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
@Tag(name = "오늘의 말씀", description = "날짜별 공개 말씀과 묵상 내용을 조회합니다.")
interface DailyWordApi {
    @Operation(summary = "오늘의 말씀 조회", description = "선택한 날짜의 공개 말씀과 공통 묵상 내용을 반환합니다. 날짜를 생략하면 서버 기준 오늘의 말씀을 조회합니다.")
    @GetMapping fun today(@Parameter(description = "공개 말씀을 조회할 날짜(YYYY-MM-DD). 생략하면 서버 기준 오늘입니다.") @RequestParam(required = false) date: LocalDate?): DailyWordResponse
}
