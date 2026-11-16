package com.example.mypasture.ai.presentation

import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam

/** Swagger 계약과 AI 답변 품질 운영 조회를 분리한 관리자 API입니다. */
@Tag(name = "관리자 · AI 피드백", description = "AI 답변 품질 평가와 우선 검토 대상을 조회합니다.")
interface AiFeedbackAdminApi {
    @Operation(summary = "AI 피드백 요약 조회", description = "관리자가 수집된 AI 답변 피드백의 집계 결과를 조회합니다.")
    @GetMapping("/feedback/summary")
    fun summary(): AiFeedbackSummaryResponse

    @Operation(summary = "우선 검토할 AI 답변 조회", description = "관리자가 품질 검토 우선순위가 높은 답변을 지정한 개수만큼 조회합니다.")
    @GetMapping("/feedback/priority")
    fun priority(@Parameter(description = "한 번에 반환할 최대 항목 수.") @RequestParam(defaultValue = "20") limit: Int): List<AiFeedbackResponse>
}
