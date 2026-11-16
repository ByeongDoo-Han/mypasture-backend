package com.example.mypasture.bible.application

import com.example.mypasture.achievement.application.AchievementService
import com.example.mypasture.bible.domain.BibleBookmarkEntity
import com.example.mypasture.bible.infrastructure.BibleBookEntityRepository
import com.example.mypasture.bible.infrastructure.BibleBookmarkRepository
import com.example.mypasture.bible.infrastructure.BibleVerseEntityRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** 절 유효성과 사용자 소유권을 확인해 말씀 저장·조회·삭제를 처리합니다. */
@Service
class BibleBookmarkService(
    private val bookRepository: BibleBookEntityRepository,
    private val verseRepository: BibleVerseEntityRepository,
    private val bookmarkRepository: BibleBookmarkRepository,
    private val achievements: AchievementService,
) {
    @Transactional
    fun save(userId: UUID, version: String, bookCode: String, chapter: Int, verse: Int): BibleBookmarkEntity {
        val verseEntity = findVerse(version, bookCode, chapter, verse)
        achievements.lockUser(userId)
        val existing = bookmarkRepository.findByUserIdAndVerse_Id(userId, verseEntity.id)
        if (existing != null) return existing
        val saved = bookmarkRepository.save(BibleBookmarkEntity(userId = userId, verse = verseEntity))
        achievements.recordProgress(userId)
        return saved
    }

    @Transactional(readOnly = true)
    fun findAll(userId: UUID, page: Int, size: Int): Page<BibleBookmarkEntity> {
        require(page >= 0) { "Page must be greater than or equal to 0." }
        require(size in 1..100) { "Size must be between 1 and 100." }
        return bookmarkRepository.findAllByUserId(userId, PageRequest.of(page, size))
    }

    @Transactional(readOnly = true)
    fun findVerseNumbers(userId: UUID, version: String, bookCode: String, chapter: Int): List<Int> {
        require(chapter > 0) { "Chapter must be greater than 0." }
        val normalizedVersion = version.uppercase()
        val book = bookRepository.findByVersionAndCode(normalizedVersion, bookCode.uppercase())
            ?: throw BibleNotFoundException("Bible book was not found: $bookCode")
        return bookmarkRepository.findVerseNumbers(userId, normalizedVersion, book.id, chapter)
    }

    @Transactional
    fun delete(userId: UUID, version: String, bookCode: String, chapter: Int, verse: Int) {
        val verseEntity = findVerse(version, bookCode, chapter, verse)
        achievements.lockUser(userId)
        achievements.recordProgress(userId)
        bookmarkRepository.deleteByUserIdAndVerse_Id(userId, verseEntity.id)
    }

    private fun findVerse(version: String, bookCode: String, chapter: Int, verse: Int) =
        bookRepository.findByVersionAndCode(version.uppercase(), bookCode.uppercase())
            ?.let { book ->
                verseRepository.findByVersionAndBook_IdAndChapterAndVerse(
                    version.uppercase(),
                    book.id,
                    chapter,
                    verse,
                )
            }
            ?: throw BibleNotFoundException("Bible verse was not found: $bookCode $chapter:$verse")
}
