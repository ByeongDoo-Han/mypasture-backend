package com.example.mypasture.daily_word.application

import com.example.mypasture.config.DailyWordProperties
import com.example.mypasture.daily_word.domain.*
import com.example.mypasture.daily_word.infrastructure.DailyWordGenerationJobRepository
import com.example.mypasture.daily_word.infrastructure.DailyWordRepository
import com.example.mypasture.daily_word.infrastructure.DailyWordRotationLockRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 검증된 목록을 날짜별로 한 번 게시하며 관리자 검수를 일일 제공의 전제에서 제거합니다. */
@Service
class DailyWordRotationService(
    private val words: DailyWordRepository,
    private val jobs: DailyWordGenerationJobRepository,
    private val guard: DailyWordRotationLockRepository,
    private val catalog: DailyWordRotationCatalog,
    private val reader: DailyWordPassageReader,
    private val properties: DailyWordProperties,
    private val publishedPort: DailyWordPublishedPort,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** 전역 잠금 뒤에는 기존 서버와 동일하게 생성 작업·날짜 행 순으로 잠급니다. */
    fun lockIfEnabled() {
        if (properties.rotationEnabled) check(guard.lock() != null) { "오늘의 말씀 배정 잠금이 준비되지 않았습니다." }
    }

    @Transactional
    fun ensurePublished(date: LocalDate, todaySnapshot: LocalDate? = null): DailyWordEntity? {
        if (!properties.rotationEnabled) return null
        val today = todaySnapshot ?: LocalDate.now(clock.withZone(SEOUL))
        if (date < today || date > today.plusDays(1)) return null
        words.findByWordDateAndStatus(date, DailyWordStatus.PUBLISHED)?.let { return it }
        lockIfEnabled()
        val activeJobs = jobs.findActiveForUpdate(date, listOf(DailyWordGenerationJobStatus.PENDING, DailyWordGenerationJobStatus.PROCESSING))
        val current = words.findByWordDateForUpdate(date)
        if (current?.status == DailyWordStatus.PUBLISHED) return current
        if (current != null && (current.source != DailyWordSource.AI || current.status != DailyWordStatus.DRAFT)) return null

        val topic = catalog.topic(date)
        val recent = if (topic == null) emptyList() else
            words.findForAdmin(date.minusDays(30), date.minusDays(1), DailyWordStatus.PUBLISHED)
        val candidates = catalog.candidates(date)
        // 최근 본문과 겹치지 않는 후보를 우선하되 작은 목록 고갈이 게시 누락을 만들지 않게 합니다.
        val ordered = candidates.filter { entry -> recent.none { old ->
            old.bookCode == entry.bookCode && old.chapter == entry.chapter &&
                old.verse <= entry.endVerse && (old.endVerse ?: old.verse) >= entry.verse
        } } + candidates
        val selection = ordered.distinctBy { it.id }.firstNotNullOfOrNull { entry ->
            reader.read(entry)?.let { entry to it }
        } ?: throw DailyWordGenerationException("순환 목록에서 게시할 수 있는 성경 원문을 찾지 못했습니다.")
        val (entry, passage) = selection
        val now = Instant.now(clock)
        val word = current ?: DailyWordEntity(
            wordDate = date, bibleVersion = passage.first.version, bookCode = entry.bookCode,
            bookName = passage.first.book.name, chapter = entry.chapter, verse = entry.verse,
            verseText = passage.first.text, meditation = "", actionQuestion = "",
        )
        word.verseId = passage.first.id
        word.bibleVersion = passage.first.version
        word.bookCode = entry.bookCode
        word.bookName = passage.first.book.name
        word.chapter = entry.chapter
        word.verse = entry.verse
        word.verseText = passage.first.text
        word.endVerse = entry.endVerse
        word.passageText = passage.text
        word.topicTags = entry.topics.joinToString(",") { it.name }
        word.readingMode = if (topic == null) DailyWordReadingMode.CONTINUOUS else DailyWordReadingMode.THEMED
        word.passageType = entry.passageType
        word.rotationEntryId = entry.id
        word.meditation = if (topic == null) "앞뒤 이야기를 이어 읽으며 이 본문에 등장하는 인물과 사건, 말씀의 흐름을 천천히 살펴보세요."
            else "오늘의 주제는 ${topic.label}입니다. 이 말씀을 천천히 읽으며 오늘의 삶에 어떻게 적용할 수 있을지 생각해 보세요."
        word.actionQuestion = if (topic == null) "이 본문에서 새롭게 발견하거나 더 알고 싶은 것은 무엇인가요?"
            else "오늘 이 말씀을 따라 실천할 한 가지는 무엇인가요?"
        word.source = DailyWordSource.CURATED
        if (current != null) word.contentRevision += 1
        word.generatedAt = now
        word.reviewedBy = null
        word.reviewedAt = null
        word.rejectionReason = null
        word.published = true
        word.status = DailyWordStatus.PUBLISHED
        word.publishedAt = now
        word.updatedAt = now
        reader.verify(word)
        activeJobs.forEach { it.cancel("요일별 본문 순환 게시로 기존 AI 작업을 종료했습니다.", now) }
        val saved = words.saveAndFlush(word)
        publishedPort.record(saved, maxOf(now, date.atStartOfDay(SEOUL).toInstant()))
        return saved
    }

    companion object { val SEOUL: ZoneId = ZoneId.of("Asia/Seoul") }
}
