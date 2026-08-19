package com.example.mypasture.bible.infrastructure

import com.example.mypasture.bible.domain.BibleCatalog
import com.example.mypasture.config.BibleProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/** 실제 1910 원본 전체를 순회해 의도한 누락 장 외의 파일과 파싱 결과를 검증합니다. */
class Kor1910SourceIntegrityTests {
    private val parser = Kor1910HtmlBibleParser(BibleProperties("data/kor_html"))

    @Test
    fun `source contains every canonical chapter except first peter five`() {
        parser.validateSourceDirectory()
        val missing = mutableListOf<String>()
        var availableChapters = 0
        var verses = 0

        BibleCatalog.books.forEach { book ->
            (1..book.chapterCount).forEach { chapter ->
                val source = parser.readChapter(book, chapter)
                if (source == null) {
                    missing += "${book.code}:$chapter"
                } else {
                    availableChapters++
                    verses += source.verses.size
                }
            }
        }

        assertEquals(listOf("1PE:5"), missing)
        assertEquals(1_188, availableChapters)
        assertEquals(30_991, verses)
        assertNotNull(parser.readChapter(BibleCatalog.findBook("PSA")!!, 1))
    }
}
