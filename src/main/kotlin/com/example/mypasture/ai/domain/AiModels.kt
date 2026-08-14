package com.example.mypasture.ai.domain

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** RAG로 검색된 성경 구절과 출처 정보를 표현하는 값 객체입니다. */
data class BibleCitation(
    val verseId: Long,
    val version: String,
    val book: String,
    val chapter: Int,
    val verse: Int,
    val text: String,
)

/** AI 답변, 근거 구절, 토큰 사용량, 비용 정보를 함께 담는 응답 모델입니다. */
data class GroundedAnswer(
    val conversationId: UUID?,
    val answer: String,
    val citations: List<BibleCitation>,
    val promptTokens: Int,
    val completionTokens: Int,
    val estimatedCostUsd: BigDecimal,
    val cacheHit: Boolean,
)

/** AI 입력 제한과 무료 사용자 사용량 제한 정책을 표현하는 도메인 모델입니다. */
data class AiUsagePolicy(
    val maxInputChars: Int,
    val freeDailyLimit: Int,
)

/** 특정 사용자의 하루 AI 사용량 집계 결과를 표현하는 도메인 모델입니다. */
data class DailyAiUsage(
    val userId: UUID,
    val dateStart: Instant,
    val count: Long,
)
