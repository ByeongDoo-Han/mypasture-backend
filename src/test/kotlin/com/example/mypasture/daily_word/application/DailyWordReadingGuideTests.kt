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
    fun `catalog guide has exact ordered neighbors and non-wrapping endpoint boundaries`() {
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
    fun `null unknown and themed catalog ids never receive continuous guide`() {
        assertNull(catalog.guide(null))
        assertNull(catalog.guide(""))
        assertNull(catalog.guide("reading-GEN-51-1"))
        for (entry in catalog.themedEntries.values.flatten()) assertNull(catalog.guide(entry.id), entry.id)
    }

    @Test
    fun `matching public snapshots expose only canonical neighboring Bible references`() {
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
    fun `mapper refuses each mismatched snapshot field rather than inferring a neighboring passage`() {
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
    fun `legacy manual and themed publication remain readable without guide`() {
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
    fun `matching metadata on manual AI draft or rejected content cannot advertise automatic guide`() {
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
    fun `public controller includes matching guide while retaining canonical first verse and range`() {
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
