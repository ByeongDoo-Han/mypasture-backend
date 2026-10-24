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
    /** 모델의 짧거나 비어 있는 항목을 본문 참조에 묶인 안전한 묵상 질문으로 보완합니다. */
    fun withSafeFallback(reference: String): GeneratedQuietTimeQuestions {
        val passage = reference.take(100)
        fun questions(values: List<String>, fallback: String): List<String> =
            values.map(String::trim).filter { it.length in 10..300 }.take(2).ifEmpty { listOf(fallback) }
        fun guide(value: String, fallback: String): String = value.trim().takeIf { it.length in 10..500 } ?: fallback

        return copy(
            observationQuestions = questions(observationQuestions, "$passage 본문에서 눈에 띄는 표현은 무엇인가요?"),
            meditationQuestions = questions(meditationQuestions, "$passage 말씀은 하나님과 사람에 대해 무엇을 알려 주나요?"),
            sharingQuestions = questions(sharingQuestions, "$passage 말씀과 연결해 나누고 싶은 생각이나 경험은 무엇인가요?"),
            actionGuide = guide(actionGuide, "오늘 읽은 본문에서 마음에 남은 한 문장을 기록해 보세요."),
            prayerGuide = guide(prayerGuide, "읽은 말씀을 바르게 이해하고 서로 존중하며 나누도록 지혜를 구해 보세요."),
        )
    }

    fun validate(): GeneratedQuietTimeQuestions {
        require(listOf(observationQuestions, meditationQuestions, sharingQuestions).all { it.size in 1..2 && it.all { q -> q.length in 10..300 } }) {
            "QT 질문의 개수 또는 길이가 올바르지 않습니다."
        }
        require(actionGuide.length in 10..500 && prayerGuide.length in 10..500) { "QT 안내문 길이가 올바르지 않습니다." }
        return this
    }
}
