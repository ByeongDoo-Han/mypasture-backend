package com.example.mypasture.ai.infrastructure

import com.example.mypasture.ai.application.BibleRetrievalPort
import com.example.mypasture.ai.domain.BibleCitation
import com.example.mypasture.bible.application.BibleReaderService
import com.example.mypasture.bible.domain.BibleCatalog
import com.example.mypasture.bible.domain.BibleVerse
import org.springframework.stereotype.Repository

/** 검증된 1910 성경 원문에서 관련 절을 검색해 AI가 사용할 RAG 근거를 제공합니다. */
@Repository
class JdbcBibleRetrievalAdapter(
    private val bibleReaderService: BibleReaderService,
) : BibleRetrievalPort {
    override fun searchRelevantVerses(question: String, limit: Int): List<BibleCitation> {
        val matches = linkedMapOf<String, BibleCitation>()
        extractSearchKeywords(question).forEach { keyword ->
            if (matches.size >= limit) return@forEach
            bibleReaderService.search(VERSION, keyword, 0, limit).verses.forEach { verse ->
                matches.putIfAbsent(verse.referenceKey(), verse.toCitation())
            }
        }
        return matches.values.take(limit).ifEmpty { defaultPastoralVerses(limit) }
    }

    /** 검색어와 직접 일치하는 절이 없을 때도 실제 원문에 존재하는 기본 목회 구절만 사용합니다. */
    private fun defaultPastoralVerses(limit: Int): List<BibleCitation> =
        DEFAULT_REFERENCES.mapNotNull { (bookCode, chapter, verse) ->
            runCatching { bibleReaderService.getVerse(VERSION, bookCode, chapter, verse).toCitation() }.getOrNull()
        }.take(limit)

    private fun BibleVerse.referenceKey() = "$bookCode:$chapter:$verse"

    private fun BibleVerse.toCitation(): BibleCitation {
        val bookOrder = BibleCatalog.findBook(bookCode)?.displayOrder ?: 0
        return BibleCitation(
            verseId = bookOrder * 1_000_000L + chapter * 1_000L + verse,
            version = version,
            book = bookName,
            chapter = chapter,
            verse = verse,
            text = text,
        )
    }

    private fun extractSearchKeywords(question: String): List<String> {
        val normalized = question.trim().lowercase()
        val directTokens = keywordRegex.findAll(normalized)
            .map { it.value }
            .filter { it.length >= 2 && it !in stopWords }
            .toMutableList()
        topicExpansions.forEach { (topic, expansions) ->
            if (normalized.contains(topic)) directTokens += expansions
        }
        return directTokens.distinct().take(MAX_KEYWORDS)
    }

    companion object {
        private const val VERSION = "KOR1910"
        private const val MAX_KEYWORDS = 12
        private val DEFAULT_REFERENCES = listOf(Triple("PHP", 4, 6), Triple("PHP", 4, 7), Triple("PSA", 23, 1), Triple("PSA", 23, 2), Triple("MAT", 6, 33))
        private val keywordRegex = Regex("[가-힣A-Za-z0-9]+")
        private val stopWords = setOf("오늘", "읽은", "말씀", "설명", "해주세요", "해줘", "위한", "대한", "나는", "제가", "입니다")
        private val topicExpansions = mapOf(
            "불안" to listOf("염려", "마음", "평강", "기도"),
            "염려" to listOf("염려", "평강", "기도"),
            "기도" to listOf("기도", "간구", "감사"),
            "감사" to listOf("감사", "기도"),
            "관계" to listOf("사랑", "용서", "서로"),
            "용서" to listOf("용서", "사랑"),
            "창세기" to listOf("창세기", "태초", "창조"),
            "위로" to listOf("목자", "쉴", "평강"),
            "힘듭" to listOf("평강", "사랑", "위로"),
        )
    }
}
