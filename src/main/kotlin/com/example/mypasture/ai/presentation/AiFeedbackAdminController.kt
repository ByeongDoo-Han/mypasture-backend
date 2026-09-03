package com.example.mypasture.ai.presentation

import com.example.mypasture.ai.application.AiAnswerFeedbackService
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 상담 원문 없이 AI 답변 평가 집계와 우선 검수 대상을 제공합니다. */
@RestController
@RequestMapping("/api/v1/admin/ai")
class AiFeedbackAdminController(
    private val service: AiAnswerFeedbackService,
) : AiFeedbackAdminApi {
    override fun summary(): AiFeedbackSummaryResponse = service.summary().let { summary ->
        AiFeedbackSummaryResponse(
            total = summary.total,
            helpful = summary.helpful,
            notHelpful = summary.notHelpful,
            regenerated = summary.regenerated,
            byReason = summary.byReason.mapKeys { it.key.name },
        )
    }

    override fun priority(limit: Int): List<AiFeedbackResponse> = service.priority(limit).map { feedback ->
        AiFeedbackResponse(
            turnId = feedback.turnId,
            rating = feedback.rating.name,
            reasons = feedback.reasons.map { it.name }.toSet(),
            regenerationStatus = feedback.regenerationStatus.name,
            regeneratedTurnId = feedback.regeneratedTurnId,
            updatedAt = feedback.updatedAt,
        )
    }
}

/** 관리자 화면에서 사용하는 비식별 AI 답변 평가 집계입니다. */
data class AiFeedbackSummaryResponse(
    val total: Long,
    val helpful: Long,
    val notHelpful: Long,
    val regenerated: Long,
    val byReason: Map<String, Long>,
)
