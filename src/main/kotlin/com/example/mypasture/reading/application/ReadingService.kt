package com.example.mypasture.reading.application

import com.example.mypasture.bible.application.BibleReaderService
import com.example.mypasture.reading.domain.BibleReadingHistoryEntity
import com.example.mypasture.reading.domain.BibleReadingStateEntity
import com.example.mypasture.reading.infrastructure.BibleReadingHistoryRepository
import com.example.mypasture.reading.infrastructure.BibleReadingStateRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** 성경 장·절 검증 후 진행률과 일별 읽기 기록을 같은 트랜잭션으로 저장합니다. */
@Service
class ReadingService(
    private val bibleReaderService: BibleReaderService,
    private val stateRepository: BibleReadingStateRepository,
    private val historyRepository: BibleReadingHistoryRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun record(userId: UUID, version: String, bookCode: String, chapter: Int, lastVerse: Int, completed: Boolean, timezone: String): BibleReadingStateEntity {
        val source = bibleReaderService.getChapter(version, bookCode, chapter)
        require(source.verses.any { it.verse == lastVerse }) { "Last verse is outside the chapter." }
        val normalizedCompleted = completed || lastVerse == source.verses.maxOf { it.verse }
        val now = Instant.now(clock)
        val state = stateRepository.findByUserIdAndBibleVersionAndBookCodeAndChapter(userId, source.version, source.book.code, chapter)
            ?: BibleReadingStateEntity(userId = userId, bibleVersion = source.version, bookCode = source.book.code, chapter = chapter, lastVerse = lastVerse, completed = normalizedCompleted, lastReadAt = now)
        state.record(lastVerse, normalizedCompleted, now)
        val readOn = LocalDate.ofInstant(now, ZoneId.of(timezone))
        val history = historyRepository.findByUserIdAndBibleVersionAndBookCodeAndChapterAndReadOn(userId, source.version, source.book.code, chapter, readOn)
            ?: BibleReadingHistoryEntity(userId = userId, bibleVersion = source.version, bookCode = source.book.code, chapter = chapter, readOn = readOn, lastVerse = lastVerse, completed = normalizedCompleted, updatedAt = now)
        history.record(lastVerse, normalizedCompleted, now)
        historyRepository.save(history)
        return stateRepository.save(state)
    }

    @Transactional(readOnly = true)
    fun states(userId: UUID): List<BibleReadingStateEntity> = stateRepository.findAllByUserIdOrderByLastReadAtDesc(userId)

    @Transactional(readOnly = true)
    fun history(userId: UUID, from: LocalDate, to: LocalDate): List<BibleReadingHistoryEntity> {
        require(!to.isBefore(from) && !from.isBefore(to.minusDays(365))) { "History range must be within 366 days." }
        return historyRepository.findAllByUserIdAndReadOnBetweenOrderByReadOnDescUpdatedAtDesc(userId, from, to)
    }
}
