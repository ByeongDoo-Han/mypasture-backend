package com.example.mypasture.daily_word.domain

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

/** 감정 주제 분류와 독립적으로 본문의 읽기 방식을 구분합니다. */
enum class DailyWordReadingMode { THEMED, CONTINUOUS }

/** 주제에 억지로 넣지 않고 성경 본문의 문학적 유형을 표시합니다. */
enum class DailyWordPassageType { TEACHING, POETRY, NARRATIVE, LAW, GENEALOGY }

/** 요일별 대표 말씀을 선택할 때 사용하는 제한된 주제 태그입니다. */
enum class DailyWordTopic(val label: String, private val evidence: Regex) {
    HOPE("소망", Regex("소망|바라|앙망")),
    GRATITUDE("감사", Regex("감사")),
    LOVE("사랑", Regex("사랑")),
    PEACE("평안", Regex("평안|평강|화평")),
    JOY("기쁨", Regex("기뻐|기쁨|기쁘|즐거|희락"));

    /** 인접 절의 설명 없이 선택된 한 절에서 주제의 직접적인 표현을 확인합니다. */
    fun appearsIn(verseText: String): Boolean = evidence.containsMatchIn(verseText)
}

/** 아직 없는 날짜의 배정도 여러 인스턴스가 한 번만 저장하도록 직렬화합니다. */
@Entity
@Table(name = "daily_word_rotation_lock")
class DailyWordRotationLockEntity(@Id val id: Int = 1)

/** 원문은 DB에서 읽으며 카탈로그에는 명시적인 범위와 선택적 주제만 보존합니다. */
data class DailyWordRotationEntry(
    val id: String,
    val bookCode: String,
    val chapter: Int,
    val verse: Int,
    val endVerse: Int,
    val topics: Set<DailyWordTopic>,
    val passageType: DailyWordPassageType,
)

/** 카탈로그의 현재 위치와 인접 본문을 제공하며 개인 읽기 상태는 포함하지 않습니다. */
data class DailyWordReadingGuide(
    val current: DailyWordRotationEntry,
    val position: Int,
    val total: Int,
    val previous: DailyWordRotationEntry?,
    val next: DailyWordRotationEntry?,
)
