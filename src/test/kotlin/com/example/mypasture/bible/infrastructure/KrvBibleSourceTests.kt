package com.example.mypasture.bible.infrastructure

import com.example.mypasture.bible.domain.BibleCatalog
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/** 배포 자산의 장·절 구조와 PDF 대표 본문을 확인합니다. */
class KrvBibleSourceTests {
    private val source = KrvBibleSource(ObjectMapper())

    @Test
    fun `complete canonical KRV source has distinct wording`() {
        source.validateSource()
        val genesis = BibleCatalog.findBook("GEN")!!
        val chapter = source.readChapter(genesis, 1)
        assertEquals(31, chapter.verses.size)
        assertEquals("태초에 하나님이 천지를 창조하시니라", chapter.verses.first().text)
        assertEquals("땅이 혼돈하고 공허하며 흑암이 깊음 위에 있고 하나님의 신은 수면에 운행하시니라", chapter.verses[1].text)
        assertNotEquals(chapter.verses.first().text, chapter.verses[1].text)
        assertEquals(14, source.readChapter(BibleCatalog.findBook("1PE")!!, 5).verses.size)
        assertEquals("주 예수의 은혜가 모든 자들에게 있을지어다 아멘", source.readChapter(BibleCatalog.findBook("REV")!!, 22).verses.last().text)
    }
}
