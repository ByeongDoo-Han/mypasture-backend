package com.example.mypasture.ai.presentation

import com.example.mypasture.ai.application.AiHistoryDetail
import com.example.mypasture.ai.application.AiHistoryPage
import com.example.mypasture.auth.infrastructure.JwtPrincipal
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import java.util.UUID

/** 사용자가 자신의 AI 목자 답변 기록만 조회·삭제하는 OpenAPI 계약입니다. */
interface AiHistoryApi {
    @GetMapping("/history")
    fun list(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) cursor: String?,
    ): ResponseEntity<AiHistoryPage>

    @GetMapping("/history/{turnId}")
    fun detail(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @PathVariable turnId: UUID,
    ): ResponseEntity<AiHistoryDetail>

    /** 선택한 답변 그룹을 삭제하고 사용자의 모든 복원 문맥 및 현재 상담 대화를 초기화합니다. */
    @DeleteMapping("/history/{turnId}")
    fun delete(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @PathVariable turnId: UUID,
    ): ResponseEntity<Void>
}
