package com.example.mypasture.ai

import com.example.mypasture.ai.application.BibleQuestionUnderstandingService
import com.example.mypasture.ai.domain.BibleQuestionIntent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 자연어 질문을 상담 목적, 검색 문장과 실제 성경 위치로 해석하는 규칙을 검증합니다. */
class BibleQuestionUnderstandingServiceTests {
    private val service = BibleQuestionUnderstandingService()

    @Test
    fun `understands an emotional concern as pastoral counseling`() {
        val result = service.understand("요즘 인간관계가 너무 힘들고 불안합니다")

        assertEquals(BibleQuestionIntent.PASTORAL_COUNSELING, result.intent)
        assertTrue(result.searchQuery.contains("위로"))
        assertTrue(result.searchQuery.contains("인간관계가 너무 힘들고 불안합니다"))
        assertTrue(result.lexicalKeywords.containsAll(listOf("사랑", "용서", "평강")))
        assertNull(result.reference)
    }

    @Test
    fun `understands prayer request without inventing a bible reference`() {
        val result = service.understand("감사 기도문을 작성해줘")

        assertEquals(BibleQuestionIntent.PRAYER, result.intent)
        assertTrue(result.searchQuery.contains("감사와 간구"))
        assertNull(result.reference)
    }

    @Test
    fun `parses an explicit Korean bible chapter and verse`() {
        val result = service.understand("창세기 1장 1절을 설명해줘")

        assertEquals(BibleQuestionIntent.BIBLE_EXPLANATION, result.intent)
        assertEquals("GEN", result.reference?.bookCode)
        assertEquals(1, result.reference?.chapter)
        assertEquals(1, result.reference?.verse)
        assertTrue(result.searchQuery.contains("검색 범위: 창세기 1장 1절"))
    }

    @Test
    fun `parses colon bible reference`() {
        val result = service.understand("요한복음 3:16의 의미가 뭐야?")

        assertEquals("JHN", result.reference?.bookCode)
        assertEquals(3, result.reference?.chapter)
        assertEquals(16, result.reference?.verse)
    }
}
