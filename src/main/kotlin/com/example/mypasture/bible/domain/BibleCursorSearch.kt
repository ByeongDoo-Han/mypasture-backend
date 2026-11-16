package com.example.mypasture.bible.domain

/** 다음 검색의 기준이 되는 정경 순서·장·절·고유 ID입니다. */
data class BibleSearchPosition(val bookOrder: Int, val chapter: Int, val verse: Int, val id: Long)

/** 저장소가 검색 본문과 다음 탐색 위치를 함께 반환합니다. */
data class BibleSearchHit(val verse: BibleVerse, val position: BibleSearchPosition)

/** 전체 개수를 계산하지 않는 성경 이름·본문 검색 결과입니다. */
data class BibleCursorSearchResult(
    val version: String,
    val keyword: String,
    val size: Int,
    val books: List<BibleBook>,
    val verses: List<BibleVerse>,
    val hasNext: Boolean,
    val nextCursor: String?,
)
