package com.example.mypasture.daily_word.application

import com.example.mypasture.ai.domain.AiRuntimeConfigSnapshot
import com.example.mypasture.ai.domain.BibleCitation
import com.example.mypasture.daily_word.domain.GeneratedDailyWord

/** 실제 성경 절을 변경하지 않고 묵상과 실천 질문만 생성하는 외부 AI port입니다. */
fun interface DailyWordGenerationPort {
    suspend fun generate(
        citation: BibleCitation,
        bookName: String,
        theme: String,
        config: AiRuntimeConfigSnapshot,
    ): GeneratedDailyWord
}
