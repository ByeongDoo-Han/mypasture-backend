package com.example.mypasture.reading.infrastructure

import com.example.mypasture.reading.domain.BibleReadingHistoryEntity
import com.example.mypasture.reading.domain.BibleReadingStateEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.util.UUID

/** 사용자별 현재 성경 읽기 위치를 저장하는 repository입니다. */
interface BibleReadingStateRepository : JpaRepository<BibleReadingStateEntity, UUID> {
    fun findByUserIdAndBibleVersionAndBookCodeAndChapter(userId: UUID, bibleVersion: String, bookCode: String, chapter: Int): BibleReadingStateEntity?
    fun findAllByUserIdOrderByLastReadAtDesc(userId: UUID): List<BibleReadingStateEntity>
}

/** 날짜별 읽기 이력을 저장하는 repository입니다. */
interface BibleReadingHistoryRepository : JpaRepository<BibleReadingHistoryEntity, UUID> {
    fun findByUserIdAndBibleVersionAndBookCodeAndChapterAndReadOn(userId: UUID, bibleVersion: String, bookCode: String, chapter: Int, readOn: LocalDate): BibleReadingHistoryEntity?
    fun findAllByUserIdAndReadOnBetweenOrderByReadOnDescUpdatedAtDesc(userId: UUID, from: LocalDate, to: LocalDate): List<BibleReadingHistoryEntity>
}
