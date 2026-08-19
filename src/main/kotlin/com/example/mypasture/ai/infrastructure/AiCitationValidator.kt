package com.example.mypasture.ai.infrastructure

import com.example.mypasture.ai.application.AiProviderException
import com.example.mypasture.ai.domain.BibleCitation
import org.springframework.stereotype.Component

/** LLM 답변의 verse ID와 장·절 표기가 RAG 검색 결과 안에만 존재하는지 후검증합니다. */
@Component
class AiCitationValidator {
    fun validate(answer: String, citations: List<BibleCitation>): List<BibleCitation> {
        val allowedById = citations.associateBy { it.verseId }
        val usedIds = CITATION_ID_REGEX.findAll(answer).map { it.groupValues[1].toLong() }.toSet()
        if (usedIds.isEmpty()) throw AiProviderException("AI 답변에 검증 가능한 성경 근거가 없습니다.")
        validateReferences(answer, citations)
        return usedIds.map(allowedById::getValue)
    }

    /** streaming 조각을 내보내기 전에 현재까지 완성된 ID와 장·절이 RAG 범위 안인지 확인합니다. */
    fun validateReferences(answer: String, citations: List<BibleCitation>) {
        val allowedIds = citations.map { it.verseId }.toSet()
        val usedIds = CITATION_ID_REGEX.findAll(answer).map { it.groupValues[1].toLong() }.toSet()
        if (!allowedIds.containsAll(usedIds)) throw AiProviderException("AI 답변이 검색되지 않은 성경 절을 인용했습니다.")

        val allowedReferences = citations.map { it.chapter to it.verse }.toSet()
        val explicitReferences = CHAPTER_VERSE_REGEX.findAll(answer)
            .map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            .toSet()
        if (!allowedReferences.containsAll(explicitReferences)) {
            throw AiProviderException("AI 답변이 검증되지 않은 장·절을 생성했습니다.")
        }
    }

    companion object {
        private val CITATION_ID_REGEX = Regex("\\[(\\d+)]")
        private val CHAPTER_VERSE_REGEX = Regex("(?<!\\d)(\\d{1,3}):(\\d{1,3})(?!\\d)")
    }
}
