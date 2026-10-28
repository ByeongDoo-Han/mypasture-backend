package com.example.mypasture.bible.application

import com.example.mypasture.bible.domain.BibleSearchPosition
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets.UTF_8
import java.text.Normalizer
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** 검색어 정규화와 cursor의 범위·검색 조건 바인딩을 검증합니다. */
class BibleSearchCursorCodecTests {
    private val position = BibleSearchPosition(1, 1, 2, 42)

    @Test fun `검색어 정규화는 NFC 공백과 유니코드 글자 수 제한을 처리한다`() {
        assertEquals("요", BibleSearchCursorCodec.normalizeKeyword(" ${Normalizer.normalize("요", Normalizer.Form.NFD)} "))
        assertEquals("😀".repeat(50), BibleSearchCursorCodec.normalizeKeyword("😀".repeat(50)))
        for (invalid in listOf("", " \t ", "가".repeat(51), "😀".repeat(51), "a\u0000b")) assertFailsWith<IllegalArgumentException> { BibleSearchCursorCodec.normalizeKeyword(invalid) }
    }

    @Test fun `커서는 정규화한 검색어와 번역본에 묶이지만 페이지 크기에는 묶이지 않는다`() {
        val cursor = BibleSearchCursorCodec.encode("kor1910", "  Word  ", position)
        assertEquals(position, BibleSearchCursorCodec.decode(cursor, "KOR1910", "word"))
        assertFailsWith<IllegalArgumentException> { BibleSearchCursorCodec.decode(cursor, "KOR1910", "world") }
        assertFailsWith<IllegalArgumentException> { BibleSearchCursorCodec.decode(cursor, "OTHER", "word") }
    }

    @Test fun `잘못된 커서 형식 버전 위치는 입력을 되돌려 보여주지 않고 거부한다`() {
        val valid = BibleSearchCursorCodec.encode("KOR1910", "요", position)
        val payload = String(Base64.getUrlDecoder().decode(valid), UTF_8)
        val invalidRevision = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.replaceFirst("1|", "2|").toByteArray(UTF_8))
        for (cursor in listOf("", "malformed!", "a", "a".repeat(513), "$valid=", invalidRevision)) {
            val error = assertFailsWith<IllegalArgumentException> { BibleSearchCursorCodec.decode(cursor, "KOR1910", "요") }
            if (cursor.length > 8) assertFalse(error.message.orEmpty().contains(cursor))
        }
        for (invalid in listOf(position.copy(bookOrder = 0), position.copy(bookOrder = 67), position.copy(chapter = 51), position.copy(verse = 0), position.copy(verse = 201), position.copy(id = 0))) {
            assertFailsWith<IllegalArgumentException> { BibleSearchCursorCodec.decode(BibleSearchCursorCodec.encode("KOR1910", "요", invalid), "KOR1910", "요") }
        }
    }

    @Test fun `정규화 결과가 같은 한글 검색어는 같은 커서를 사용한다`() {
        val cursor = BibleSearchCursorCodec.encode("KOR1910", "요", position)
        assertEquals(position, BibleSearchCursorCodec.decode(cursor, "KOR1910", Normalizer.normalize("요", Normalizer.Form.NFD)))
    }
}
