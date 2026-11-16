package com.example.mypasture.daily_word.application

import com.example.mypasture.bible.domain.BibleCatalog
import com.example.mypasture.daily_word.domain.DailyWordEntity
import com.example.mypasture.daily_word.domain.DailyWordReadingMode
import com.example.mypasture.daily_word.domain.DailyWordRotationEntry
import com.example.mypasture.daily_word.domain.DailyWordSource
import com.example.mypasture.daily_word.domain.DailyWordStatus
import com.example.mypasture.daily_word.presentation.DailyWordReadingGuideMapper
import com.example.mypasture.daily_word.presentation.DailyWordController
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.LocalDate

/** 공개 읽기 안내가 현재 저장 본문과 일치하고 개인 완독이나 다음 날짜를 추정하지 않는지 검증합니다. */
class DailyWordReadingGuideTests {
    private val catalog = DailyWordRotationCatalog()
    private val mapper = DailyWordReadingGuideMapper(catalog)

    @Test
    fun `연속 읽기 안내는 정해진 순서와 끝 경계를 정확히 지키고 순환하지 않는다`() {
        val entries = catalog.continuousEntries
        for ((index, entry) in entries.withIndex()) {
            val guide = requireNotNull(catalog.guide(entry.id))
            assertEquals(entry, guide.current)
            assertEquals(index + 1, guide.position)
            assertEquals(entries.size, guide.total)
            assertEquals(entries.getOrNull(index - 1), guide.previous)
            assertEquals(entries.getOrNull(index + 1), guide.next)
        }
        assertNull(requireNotNull(catalog.guide(entries.first().id)).previous)
        assertNull(requireNotNull(catalog.guide(entries.last().id)).next)
        assertEquals(50, entries.last().chapter)
        assertEquals(26, entries.last().endVerse)
    }

    @Test
    fun `없는 ID 주제별 ID에는 연속 읽기 안내를 제공하지 않는다`() {
        assertNull(catalog.guide(null))
        assertNull(catalog.guide(""))
        assertNull(catalog.guide("reading-GEN-51-1"))
        for (entry in catalog.themedEntries.values.flatten()) assertNull(catalog.guide(entry.id), entry.id)
    }

    @Test
    fun `일치하는 공개 스냅샷에는 정규 성경의 이웃 위치만 제공한다`() {
        val entries = catalog.continuousEntries
        for ((index, entry) in entries.withIndex()) {
            val snapshot = word(entry).apply { bookName = "stored display name must not replace canonical neighbor names" }
            val guide = requireNotNull(mapper.from(snapshot))
            assertEquals(index + 1, guide.position)
            assertEquals(entries.size, guide.total)
            val previous = entries.getOrNull(index - 1)
            if (previous == null) assertNull(guide.previous) else {
                val ref = requireNotNull(guide.previous)
                assertEquals(BibleCatalog.KOR1910_VERSION, ref.version)
                assertEquals(previous.bookCode, ref.bookCode)
                assertEquals("창세기", ref.bookName)
                assertEquals(previous.chapter, ref.chapter)
                assertEquals(previous.verse, ref.verse)
                assertEquals(previous.endVerse, ref.endVerse)
            }
            val next = entries.getOrNull(index + 1)
            if (next == null) assertNull(guide.next) else {
                val ref = requireNotNull(guide.next)
                assertEquals(BibleCatalog.KOR1910_VERSION, ref.version)
                assertEquals(next.bookCode, ref.bookCode)
                assertEquals("창세기", ref.bookName)
                assertEquals(next.chapter, ref.chapter)
                assertEquals(next.verse, ref.verse)
                assertEquals(next.endVerse, ref.endVerse)
            }
            assertEquals("stored display name must not replace canonical neighbor names", snapshot.bookName)
            assertEquals(entry.id, snapshot.rotationEntryId)
            assertEquals(entry.endVerse, snapshot.endVerse)
        }
    }

    @Test
    fun `스냅샷 필드가 하나라도 맞지 않으면 이웃 본문을 추정하지 않는다`() {
        val entry = catalog.continuousEntries.first()
        val mismatches: List<Pair<String, (DailyWordEntity) -> Unit>> = listOf(
            "book" to { it.bookCode = "EXO" },
            "chapter" to { it.chapter = 2 },
            "start verse" to { it.verse = entry.verse + 1 },
            "end verse" to { it.endVerse = entry.endVerse + 1 },
            "missing end verse" to { it.endVerse = null },
            "missing id" to { it.rotationEntryId = null },
            "blank id" to { it.rotationEntryId = "" },
            "unknown id" to { it.rotationEntryId = "reading-GEN-51-1" },
            "another valid id" to { it.rotationEntryId = catalog.continuousEntries[1].id },
            "themed id" to { it.rotationEntryId = catalog.themedEntries.values.first().first().id },
            "themed mode" to { it.readingMode = DailyWordReadingMode.THEMED },
            "legacy mode" to { it.readingMode = null },
            "unsupported version" to { it.bibleVersion = "OTHER" },
            "blank version" to { it.bibleVersion = "" },
            "noncanonical version case" to { it.bibleVersion = "kor1910" },
        )
        for ((label, mutate) in mismatches) {
            val snapshot = word(entry)
            mutate(snapshot)
            assertNull(mapper.from(snapshot), label)
        }
    }

    @Test
    fun `기존 수동 게시와 주제별 게시도 안내 없이 조회할 수 있다`() {
        val legacy = word(catalog.continuousEntries.first()).apply {
            source = DailyWordSource.MANUAL
            readingMode = null
            rotationEntryId = null
            endVerse = null
            passageText = null
        }
        assertNull(mapper.from(legacy))
        assertEquals("canonical first verse", legacy.verseText)
        val themed = word(catalog.themedEntries.values.first().first()).apply { readingMode = DailyWordReadingMode.THEMED }
        assertNull(mapper.from(themed))
        assertEquals("canonical first verse", themed.verseText)
    }

    @Test
    fun `수동 AI 초안이나 거부된 콘텐츠는 메타데이터가 일치해도 자동 안내를 표시하지 않는다`() {
        val entry = catalog.continuousEntries.first()
        for (source in listOf(DailyWordSource.MANUAL, DailyWordSource.AI)) {
            val snapshot = word(entry).apply { this.source = source }
            assertNull(mapper.from(snapshot), source.name)
        }
        for (status in listOf(DailyWordStatus.DRAFT, DailyWordStatus.REJECTED)) {
            val snapshot = word(entry).apply { this.status = status }
            assertNull(mapper.from(snapshot), status.name)
        }
    }

    @Test
    fun `공개 API는 일치하는 안내를 제공하면서 정규 첫 절과 범위를 보존한다`() {
        val snapshot = word(catalog.continuousEntries[6])
        val service = Mockito.mock(DailyWordService::class.java)
        Mockito.`when`(service.getPublished(snapshot.wordDate)).thenReturn(snapshot)
        val response = DailyWordController(service, mapper).today(snapshot.wordDate)
        assertEquals(snapshot.wordDate, response.date)
        assertEquals(snapshot.verseText, response.verseText)
        assertEquals(snapshot.passageText, response.passageText)
        assertEquals(snapshot.endVerse, response.endVerse)
        assertEquals(mapper.from(snapshot), response.readingGuide)
        assertEquals(7, requireNotNull(response.readingGuide).position)
        Mockito.verify(service).getPublished(snapshot.wordDate)
    }

    private fun word(entry: DailyWordRotationEntry) = DailyWordEntity(
        wordDate = LocalDate.of(2026, 10, 10),
        bibleVersion = BibleCatalog.KOR1910_VERSION,
        bookCode = entry.bookCode,
        bookName = "창세기",
        chapter = entry.chapter,
        verse = entry.verse,
        endVerse = entry.endVerse,
        verseText = "canonical first verse",
        passageText = "fixture passage, not production source text",
        meditation = "fixture",
        actionQuestion = "fixture",
        status = DailyWordStatus.PUBLISHED,
        published = true,
        source = DailyWordSource.CURATED,
        readingMode = DailyWordReadingMode.CONTINUOUS,
        rotationEntryId = entry.id,
    )
}
