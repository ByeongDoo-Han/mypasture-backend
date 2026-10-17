package com.example.mypasture.quiet_time.application

import com.example.mypasture.quiet_time.domain.QuietTimeQuestionStyle
import java.math.BigDecimal

/** 실제 본문에 근거해 목장 공용 QT 질문을 생성하는 출력 포트입니다. */
interface QuietTimeQuestionGenerationPort {
    suspend fun generate(reference: String, selectedText: String, contextText: String, style: QuietTimeQuestionStyle): GeneratedQuietTimeQuestions
}

/** 검증을 마친 질문과 AI 사용량을 영속화하기 위한 생성 결과입니다. */
data class GeneratedQuietTimeQuestions(
    val observationQuestions: List<String>,
    val meditationQuestions: List<String>,
    val sharingQuestions: List<String>,
    val actionGuide: String,
    val prayerGuide: String,
    val model: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val estimatedCostUsd: BigDecimal,
) {
    fun validate(): GeneratedQuietTimeQuestions {
        require(listOf(observationQuestions, meditationQuestions, sharingQuestions).all { it.size in 1..2 && it.all { q -> q.length in 10..300 } }) {
            "QT 질문의 개수 또는 길이가 올바르지 않습니다."
        }
        require(actionGuide.length in 10..500 && prayerGuide.length in 10..500) { "QT 안내문 길이가 올바르지 않습니다." }
        return this
    }
}
