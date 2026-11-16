package com.example.mypasture.quiet_time.application

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** AI가 짧은 질문을 반환해도 생성 요청을 실패시키지 않는지 검증합니다. */
class GeneratedQuietTimeQuestionsTests {
    @Test
    fun `모델 출력이 짧거나 비어 있으면 말씀 근거 질문으로 대체하고 사용량을 보존한다`() {
        val generated = GeneratedQuietTimeQuestions(
            observationQuestions = listOf("왜?", "본문에서 강조되는 표현은 무엇인가요?"),
            meditationQuestions = emptyList(),
            sharingQuestions = listOf("나눔?"),
            actionGuide = "실천", prayerGuide = "기도",
            model = "test-model", promptTokens = 42, completionTokens = 17,
            estimatedCostUsd = BigDecimal("0.000123"),
        )

        val repaired = generated.withSafeFallback("요한복음 13:34").validate()

        assertEquals(listOf("본문에서 강조되는 표현은 무엇인가요?"), repaired.observationQuestions)
        assertTrue(repaired.meditationQuestions.single().contains("요한복음 13:34"))
        assertTrue(repaired.sharingQuestions.single().contains("요한복음 13:34"))
        assertEquals(generated.model, repaired.model)
        assertEquals(generated.estimatedCostUsd, repaired.estimatedCostUsd)
    }
}
