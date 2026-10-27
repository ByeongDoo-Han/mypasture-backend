package com.example.mypasture.ai

import com.example.mypasture.ai.application.AiProviderException
import com.example.mypasture.ai.application.AiProviderFailureKind
import com.example.mypasture.ai.domain.BibleCitation
import com.example.mypasture.ai.infrastructure.AiCitationValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** AI 답변이 검색된 성경 근거 밖의 verse ID나 장·절을 만들지 못하게 검증합니다. */
class AiCitationValidatorTests {
    private val validator = AiCitationValidator()
    private val citation = BibleCitation(101, "KOR1910", "시편", 23, 1, "여호와는 나의 목자시니")

    @Test
    fun `returns only citations actually used by answer`() {
        val used = validator.validate("시편 23:1의 의미를 묵상해 보세요 [101]", listOf(citation))

        assertEquals(listOf(citation), used)
    }

    @Test
    fun `rejects answer without citation marker`() {
        assertThrows(AiProviderException::class.java) {
            validator.validate("말씀을 묵상해 보세요", listOf(citation))
        }
    }

    @Test
    fun `rejects verse id not returned by retrieval`() {
        assertThrows(AiProviderException::class.java) {
            validator.validate("근거입니다 [999]", listOf(citation))
        }
    }

    @Test
    fun `rejects invented chapter and verse`() {
        val error = assertThrows(AiProviderException::class.java) {
            validator.validate("시편 99:99를 읽어보세요 [101]", listOf(citation))
        }
        assertEquals(AiProviderFailureKind.INVALID_CITATION, error.failureKind)
    }
}
