package com.example.mypasture.ai.application

import com.example.mypasture.ai.domain.BiblePassageReference
import com.example.mypasture.ai.domain.BibleQuestionIntent
import com.example.mypasture.ai.domain.UnderstoodBibleQuestion
import com.example.mypasture.bible.domain.BibleBook
import com.example.mypasture.bible.domain.BibleCatalog
import org.springframework.stereotype.Service

/** 사용자 문장을 상담 목적과 성경 참조로 해석하고 의미 검색용 질의를 만듭니다. */
@Service
class BibleQuestionUnderstandingService {
    fun understand(question: String): UnderstoodBibleQuestion {
        val normalized = question.trim()
        require(normalized.isNotBlank()) { "AI question must not be blank." }

        val reference = parseReference(normalized)
        val intent = classifyIntent(normalized, reference)
        return UnderstoodBibleQuestion(
            originalQuestion = normalized,
            searchQuery = buildSemanticQuery(normalized, intent, reference),
            intent = intent,
            lexicalKeywords = extractSearchKeywords(normalized, intent),
            reference = reference,
        )
    }

    private fun classifyIntent(question: String, reference: BiblePassageReference?): BibleQuestionIntent =
        when {
            question.containsAny("기도문", "기도해", "기도를 써", "기도 작성") -> BibleQuestionIntent.PRAYER
            question.containsAny("묵상", "큐티", "qt", "실천 질문") -> BibleQuestionIntent.DEVOTIONAL
            question.containsAny("설명", "해석", "뜻", "의미", "배경", "내용") || reference != null ->
                BibleQuestionIntent.BIBLE_EXPLANATION
            question.containsAny("추천", "어떤 말씀", "말씀을 찾아", "구절을 찾아", "말씀이 필요") ->
                BibleQuestionIntent.VERSE_RECOMMENDATION
            question.containsAny(
                "불안", "걱정", "염려", "두려", "힘들", "외롭", "우울", "슬프", "화가", "분노",
                "관계", "갈등", "용서", "상처", "진로", "직장", "가족", "고민",
            ) -> BibleQuestionIntent.PASTORAL_COUNSELING
            else -> BibleQuestionIntent.BIBLE_QA
        }

    private fun buildSemanticQuery(
        question: String,
        intent: BibleQuestionIntent,
        reference: BiblePassageReference?,
    ): String {
        val purpose = when (intent) {
            BibleQuestionIntent.PASTORAL_COUNSELING -> "삶의 상황을 이해하고 위로, 소망, 회복과 지혜를 주는 성경 구절"
            BibleQuestionIntent.BIBLE_EXPLANATION -> "본문의 의미, 문맥과 핵심 메시지를 설명하는 성경 구절"
            BibleQuestionIntent.PRAYER -> "기도의 근거가 되는 하나님의 성품, 약속, 감사와 간구에 관한 성경 구절"
            BibleQuestionIntent.VERSE_RECOMMENDATION -> "사용자의 필요와 주제에 직접 관련된 성경 구절"
            BibleQuestionIntent.DEVOTIONAL -> "묵상과 일상 실천으로 이어지는 성경 구절"
            BibleQuestionIntent.BIBLE_QA -> "질문에 성경적으로 답하는 데 직접 근거가 되는 성경 구절"
        }
        val scope = reference?.let {
            buildString {
                append(" 검색 범위: ${it.bookName}")
                it.chapter?.let { chapter -> append(" ${chapter}장") }
                it.verse?.let { verse -> append(" ${verse}절") }
                append('.')
            }
        }.orEmpty()
        return "$purpose.$scope 사용자 질문: $question"
    }

    private fun parseReference(question: String): BiblePassageReference? {
        val book = BibleCatalog.books
            .sortedByDescending { it.name.length }
            .firstOrNull { containsBook(question, it) }
            ?: return null
        val matchedName = listOf(book.name, book.code).first { question.contains(it, ignoreCase = true) }
        val location = Regex(
            "${Regex.escape(matchedName)}\\s*(?:(\\d{1,3})\\s*(?:장\\s*(?:(\\d{1,3})\\s*절?)?|[:：]\\s*(\\d{1,3}))?)?",
            RegexOption.IGNORE_CASE,
        ).find(question)
        val chapter = location?.groupValues?.getOrNull(1)?.toIntOrNull()
        val verse = location?.groupValues?.getOrNull(2)?.toIntOrNull()
            ?: location?.groupValues?.getOrNull(3)?.toIntOrNull()
        return BiblePassageReference(
            bookCode = book.code,
            bookName = book.name,
            chapter = chapter?.takeIf { it in 1..book.chapterCount },
            verse = verse?.takeIf { it > 0 },
        )
    }

    private fun containsBook(question: String, book: BibleBook): Boolean =
        question.contains(book.name, ignoreCase = true) ||
            Regex("(?:^|[^A-Za-z0-9])${Regex.escape(book.code)}(?:$|[^A-Za-z0-9])", RegexOption.IGNORE_CASE).containsMatchIn(question)

    private fun extractSearchKeywords(question: String, intent: BibleQuestionIntent): List<String> {
        val normalized = question.lowercase()
        val keywords = keywordRegex.findAll(normalized)
            .map { it.value }
            .filter { it.length >= 2 && it !in stopWords }
            .toMutableList()
        topicExpansions.forEach { (topic, expansions) ->
            if (normalized.contains(topic)) keywords += expansions
        }
        keywords += intentFallbacks.getValue(intent)
        return keywords.distinct().take(MAX_KEYWORDS)
    }

    private fun String.containsAny(vararg candidates: String): Boolean = candidates.any { contains(it, ignoreCase = true) }

    companion object {
        private const val MAX_KEYWORDS = 16
        private val keywordRegex = Regex("[가-힣A-Za-z0-9]+")
        private val stopWords = setOf("오늘", "읽은", "말씀", "설명", "해주세요", "해줘", "위한", "대한", "나는", "제가", "입니다")
        private val topicExpansions = mapOf(
            "불안" to listOf("염려", "두려움", "마음", "평강", "기도"),
            "걱정" to listOf("염려", "평강", "맡기"),
            "기도" to listOf("기도", "간구", "감사"),
            "관계" to listOf("사랑", "용서", "서로", "화목"),
            "용서" to listOf("용서", "사랑", "긍휼"),
            "외롭" to listOf("함께", "위로", "버리지"),
            "힘들" to listOf("환난", "인내", "소망", "위로"),
            "진로" to listOf("길", "지혜", "인도"),
            "창세기" to listOf("태초", "창조"),
        )
        private val intentFallbacks = mapOf(
            BibleQuestionIntent.PASTORAL_COUNSELING to listOf("위로", "소망", "평강"),
            BibleQuestionIntent.BIBLE_EXPLANATION to listOf("말씀", "진리"),
            BibleQuestionIntent.PRAYER to listOf("기도", "간구", "감사"),
            BibleQuestionIntent.VERSE_RECOMMENDATION to listOf("말씀", "지혜"),
            BibleQuestionIntent.DEVOTIONAL to listOf("묵상", "행하", "마음"),
            BibleQuestionIntent.BIBLE_QA to emptyList(),
        )
    }
}
