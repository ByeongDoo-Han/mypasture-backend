package com.example.mypasture.worship.application

import com.example.mypasture.bible.application.BibleReaderService
import com.example.mypasture.worship.domain.WorshipRecordEntity
import com.example.mypasture.worship.domain.WorshipServiceType
import com.example.mypasture.worship.infrastructure.WorshipRecordRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** 예배 기록을 생성하거나 수정할 때 전달하는 값입니다. */
data class WorshipRecordInput(
    val worshipDate: LocalDate,
    val serviceType: WorshipServiceType,
    val title: String?,
    val bibleVersion: String,
    val bookCode: String,
    val chapter: Int,
    val startVerse: Int,
    val endVerse: Int,
    val heard: String,
    val lifeConnection: String?,
    val personalResponse: String?,
    val prayer: String?,
)

/** 사용자 소유 기록이 없을 때 발생합니다. */
class WorshipRecordNotFoundException : RuntimeException("Worship record was not found.")

/** 오래된 수정 버전으로 저장을 시도할 때 발생합니다. */
class WorshipRecordConflictException : RuntimeException("Worship record was changed by another request.")

/** 예배 기록의 말씀 범위, 작성자 소유권과 수정 버전을 검증합니다. */
@Service
class WorshipRecordService(
    private val records: WorshipRecordRepository,
    private val bible: BibleReaderService,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional(readOnly = true)
    fun list(userId: UUID, page: Int, size: Int): Page<WorshipRecordEntity> {
        require(page >= 0 && size in 1..100) { "Invalid page or size." }
        return records.findAllByUserId(
            userId,
            PageRequest.of(page, size, Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id"))),
        )
    }

    @Transactional(readOnly = true)
    fun get(userId: UUID, id: UUID): WorshipRecordEntity = owned(userId, id)

    @Transactional
    fun create(userId: UUID, input: WorshipRecordInput): WorshipRecordEntity {
        val data = validate(input)
        val now = Instant.now(clock)
        return records.save(
            WorshipRecordEntity(
                userId = userId,
                worshipDate = input.worshipDate,
                serviceType = input.serviceType,
                title = data.title,
                bibleVersion = data.version,
                bookCode = data.bookCode,
                bookName = data.bookName,
                chapter = input.chapter,
                startVerse = input.startVerse,
                endVerse = input.endVerse,
                heard = data.heard,
                lifeConnection = data.lifeConnection,
                personalResponse = data.personalResponse,
                prayer = data.prayer,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    @Transactional
    fun update(userId: UUID, id: UUID, expectedRevision: Long, input: WorshipRecordInput): WorshipRecordEntity {
        require(expectedRevision >= 0) { "Expected revision must be positive or zero." }
        val record = owned(userId, id)
        if (record.revision != expectedRevision) throw WorshipRecordConflictException()
        val data = validate(input)
        record.worshipDate = input.worshipDate
        record.serviceType = input.serviceType
        record.title = data.title
        record.bibleVersion = data.version
        record.bookCode = data.bookCode
        record.bookName = data.bookName
        record.chapter = input.chapter
        record.startVerse = input.startVerse
        record.endVerse = input.endVerse
        record.heard = data.heard
        record.lifeConnection = data.lifeConnection
        record.personalResponse = data.personalResponse
        record.prayer = data.prayer
        record.updatedAt = Instant.now(clock)
        return records.saveAndFlush(record)
    }

    @Transactional
    fun delete(userId: UUID, id: UUID) = records.delete(owned(userId, id))

    private fun owned(userId: UUID, id: UUID) =
        records.findByIdAndUserId(id, userId) ?: throw WorshipRecordNotFoundException()

    private fun validate(input: WorshipRecordInput): Validated {
        val version = input.bibleVersion.trim().uppercase()
        val bookCode = input.bookCode.trim().uppercase()
        require(version.length in 1..40 && bookCode.length in 1..40) { "Invalid Bible version or book." }
        require(input.chapter > 0 && input.startVerse > 0 && input.endVerse >= input.startVerse && input.endVerse - input.startVerse < 50) {
            "Select 1 to 50 consecutive verses within one chapter."
        }
        val chapter = bible.getChapter(version, bookCode, input.chapter)
        val available = chapter.verses.map { it.verse }.toSet()
        require((input.startVerse..input.endVerse).all(available::contains)) { "Bible passage was not found." }
        val heard = input.heard.trim()
        require(heard.length in 1..2000) { "Heard message must be between 1 and 2000 characters." }
        fun optional(value: String?, max: Int) = value?.trim()?.takeIf { it.isNotEmpty() }?.also {
            require(it.length <= max) { "Text must be at most $max characters." }
        }
        return Validated(
            version, bookCode, chapter.book.name, optional(input.title, 120), heard,
            optional(input.lifeConnection, 2000), optional(input.personalResponse, 2000), optional(input.prayer, 2000),
        )
    }

    /** 정규화와 성경 본문 확인을 마친 저장 값입니다. */
    private data class Validated(
        val version: String,
        val bookCode: String,
        val bookName: String,
        val title: String?,
        val heard: String,
        val lifeConnection: String?,
        val personalResponse: String?,
        val prayer: String?,
    )
}
