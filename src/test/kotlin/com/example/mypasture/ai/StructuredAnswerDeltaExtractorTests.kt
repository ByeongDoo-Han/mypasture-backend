package com.example.mypasture.ai

import com.example.mypasture.ai.application.AiProviderException
import com.example.mypasture.ai.infrastructure.StructuredAnswerDeltaExtractor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 분할된 구조화 JSON에서 실제 답변만 읽고 이스케이프 경계를 보존하는지 확인합니다. */
class StructuredAnswerDeltaExtractorTests {
    @Test
    fun `주변 JSON이 완성되기 전에도 답변을 추출한다`() {
        val extractor = StructuredAnswerDeltaExtractor()
        assertEquals("", extractor.accept("{\"ans"))
        assertEquals("근거: [42] 첫 문장", extractor.accept("wer\":\"근거: [42] 첫 문장"))
        assertFalse(extractor.complete)
        assertEquals("\n둘째 문장", extractor.accept("\\n둘째 문장\",\"used_verse_ids\":[42]}"))
        assertTrue(extractor.complete)
    }

    @Test
    fun `청크 경계를 넘어 이스케이프 문자와 서로게이트 쌍을 유지한다`() {
        val extractor = StructuredAnswerDeltaExtractor()
        assertEquals("근거: [42] ", extractor.accept("{\"answer\":\"근거: [42] \\"))
        assertEquals("\"과 ", extractor.accept("\"과 \\uD83D"))
        assertEquals("😊", extractor.accept("\\uDE0A\"}"))
    }

    @Test
    fun `잘못된 답변 이스케이프를 거부한다`() {
        val extractor = StructuredAnswerDeltaExtractor()
        assertThrows(AiProviderException::class.java) {
            extractor.accept("{\"answer\":\"잘못된 \\x 문자\"}")
        }
    }
}
