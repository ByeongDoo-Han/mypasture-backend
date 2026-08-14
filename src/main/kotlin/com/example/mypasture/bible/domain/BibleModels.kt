package com.example.mypasture.bible.domain

/** 앱에서 제공하는 성경 번역본의 메타데이터를 표현하는 도메인 모델입니다. */
data class BibleVersion(
    val code: String,
    val name: String,
    val language: String,
    val copyright: String,
)

/** 성경 66권의 코드, 표시명, 순서, 장 수를 표현하는 도메인 모델입니다. */
data class BibleBook(
    val code: String,
    val name: String,
    val testament: Testament,
    val displayOrder: Int,
    val chapterCount: Int,
)

/** 구약/신약 구분을 표현하는 enum입니다. */
enum class Testament {
    OLD,
    NEW,
}

/** 특정 절 하나의 본문과 위치를 표현하는 도메인 모델입니다. */
data class BibleVerse(
    val version: String,
    val bookCode: String,
    val bookName: String,
    val chapter: Int,
    val verse: Int,
    val text: String,
)

/** 한 장 전체를 읽기 화면에서 바로 렌더링할 수 있도록 묶은 도메인 모델입니다. */
data class BibleChapter(
    val version: String,
    val book: BibleBook,
    val chapter: Int,
    val previous: BibleChapterPointer?,
    val next: BibleChapterPointer?,
    val verses: List<BibleVerse>,
)

/** 성경 읽기 화면의 이전/다음 장 이동에 필요한 최소 위치 정보입니다. */
data class BibleChapterPointer(
    val bookCode: String,
    val bookName: String,
    val chapter: Int,
)

/** 단어 검색 결과와 페이지 정보를 함께 표현하는 도메인 모델입니다. */
data class BibleSearchResult(
    val version: String,
    val keyword: String,
    val page: Int,
    val size: Int,
    val total: Int,
    val hasNext: Boolean,
    val verses: List<BibleVerse>,
)
