package com.example.mypasture.ai.presentation

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam

/** Swagger 계약과 AI 답변 품질 운영 조회를 분리한 관리자 API입니다. */
interface AiFeedbackAdminApi {
    @GetMapping("/feedback/summary")
    fun summary(): AiFeedbackSummaryResponse

    @GetMapping("/feedback/priority")
    fun priority(@RequestParam(defaultValue = "20") limit: Int): List<AiFeedbackResponse>
}
