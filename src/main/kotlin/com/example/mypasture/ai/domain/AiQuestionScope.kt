package com.example.mypasture.ai.domain

import java.math.BigDecimal

/** 질문의 실제 도움 목적을 성경·종교·목회 상담 지원 범위로 구분합니다. */
enum class AiQuestionScope { RELATED, PASTORAL_SUPPORT, UNRELATED, UNCLEAR }

/** 검증된 의미 판단과 별도로 제공자가 보고한 평가 사용량을 보존합니다. */
data class AiQuestionScopeAssessment(
    val scope: AiQuestionScope,
    val uncertain: Boolean,
    val validated: Boolean = true,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val estimatedCostUsd: BigDecimal = BigDecimal.ZERO,
    val evaluatorModel: String? = null,
)
