package com.example.mypasture.ai.presentation

import com.example.mypasture.ai.application.AiHistoryDetail
import com.example.mypasture.ai.application.AiHistoryPage
import com.example.mypasture.auth.infrastructure.JwtPrincipal
import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import java.util.UUID

/** 사용자가 자신의 AI 목자 답변 기록만 조회·삭제하는 OpenAPI 계약입니다. */
@Tag(name = "AI 목자 기록", description = "사용자의 비공개 AI 상담 기록을 관리합니다.")
interface AiHistoryApi {
    @Operation(summary = "내 AI 답변 기록 목록", description = "로그인한 사용자의 답변 기록만 커서 기반으로 조회합니다.")
    @GetMapping("/history")
    fun list(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Parameter(description = "한 번에 반환할 최대 항목 수.") @RequestParam(defaultValue = "20") size: Int,
        @Parameter(description = "다음 결과를 조회할 커서. 첫 요청에서는 생략합니다.") @RequestParam(required = false) cursor: String?,
    ): ResponseEntity<AiHistoryPage>

    @Operation(summary = "내 AI 답변 기록 조회", description = "선택한 답변 그룹의 질문·답변 상세를 소유자에게만 반환합니다.")
    @GetMapping("/history/{turnId}")
    fun detail(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Parameter(description = "본인 AI 답변 턴의 UUID.") @PathVariable turnId: UUID,
    ): ResponseEntity<AiHistoryDetail>

    /** 선택한 답변 그룹을 삭제하고 사용자의 모든 복원 문맥 및 현재 상담 대화를 초기화합니다. */
    @Operation(summary = "내 AI 답변 기록 삭제", description = "선택한 답변 그룹을 삭제하고 해당 사용자의 복원 문맥과 현재 상담 대화를 초기화합니다.")
    @DeleteMapping("/history/{turnId}")
    fun delete(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Parameter(description = "본인 AI 답변 턴의 UUID.") @PathVariable turnId: UUID,
    ): ResponseEntity<Void>
}
