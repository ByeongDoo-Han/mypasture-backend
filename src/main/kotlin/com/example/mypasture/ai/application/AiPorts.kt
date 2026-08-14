package com.example.mypasture.ai.application

import com.example.mypasture.ai.domain.BibleCitation
import com.example.mypasture.ai.domain.GroundedAnswer
import java.util.UUID

/** 성경 구절 검색 구현체를 application layer와 분리하는 RAG 검색 포트입니다. */
interface BibleRetrievalPort {
    fun searchRelevantVerses(
        question: String,
        limit: Int = 10,
    ): List<BibleCitation>
}

/** OpenAI 같은 LLM 호출 구현체를 application layer와 분리하는 completion 포트입니다. */
interface AiCompletionPort {
    fun answerWithCitations(
        question: String,
        citations: List<BibleCitation>,
    ): GroundedAnswer
}

/** 동일하거나 재사용 가능한 AI 질문 답변을 저장해 비용을 줄이는 캐시 포트입니다. */
interface AiCachePort {
    fun get(question: String): GroundedAnswer?

    fun put(
        question: String,
        answer: GroundedAnswer,
    )
}

/** AI 사용 횟수, 토큰, 예상 비용을 기록하고 quota 판단에 사용하는 사용량 포트입니다. */
interface AiUsagePort {
    fun countToday(userId: UUID): Long

    fun save(
        userId: UUID,
        question: String,
        answer: GroundedAnswer,
    )
}
