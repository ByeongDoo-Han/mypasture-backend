package com.example.mypasture.bible.infrastructure

import com.example.mypasture.bible.domain.BibleCatalog
import com.example.mypasture.config.BibleProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/** 실제 1910 원본 전체를 순회해 파일, 절 수와 보강 본문을 검증합니다. */
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
        assertEquals(31_004, verses)
        assertNotNull(parser.readChapter(BibleCatalog.findBook("PSA")!!, 1))
    }

    @Test
    fun `Genesis contains all 1533 verses including restored passages`() {
        val genesis = BibleCatalog.findBook("GEN")!!
        val chapters = (1..genesis.chapterCount).map { chapter ->
            requireNotNull(parser.readChapter(genesis, chapter))
        }

        assertEquals(1_533, chapters.sumOf { it.verses.size })
        assertEquals((1..43).toList(), chapters[29].verses.map { it.verse })
        assertEquals((1..20).toList(), chapters[32].verses.map { it.verse })
        assertEquals(
            "오늘 내가 외삼촌의 양떼로 두루 다니며 그 양 중에 아롱진 자와 점있는 자와 검은 자를 가리어 내며 염소 중에 점 있는 자와 아롱진 자를 가리어 내리니 이같은 것이 나면 나의 삯이 되리이다",
            chapters[29].verses.first { it.verse == 32 }.text,
        )
        assertEquals(
            "거기 단을 쌓고 그 이름을 엘엘로헤이스라엘이라 하였더라",
            chapters[32].verses.first { it.verse == 20 }.text,
        )
    }
}
