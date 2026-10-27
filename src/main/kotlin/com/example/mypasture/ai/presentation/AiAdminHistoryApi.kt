package com.example.mypasture.ai.presentation

import com.example.mypasture.ai.application.AiAdminHistoryDetail
import com.example.mypasture.ai.application.AiAdminHistoryPage
import com.example.mypasture.ai.application.AiAdminHistoryReviewReason
import com.example.mypasture.auth.infrastructure.JwtPrincipal
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import java.util.UUID

/** 원문 열람 사유를 감사 로그에 남기기 위한 요청입니다. */
data class AiAdminHistoryReviewRequest(val reason: AiAdminHistoryReviewReason)

/** 권한 있는 관리자의 AI 목자 기록 조회 계약입니다. */
@Tag(name = "관리자 · AI 목자 기록", description = "관리자의 AI 질문 목록과 사유를 남기는 답변 상세 열람입니다.")
interface AiAdminHistoryApi {
    @Operation(summary = "AI 목자 질문 목록 조회", description = "활성 사용자의 보유기간 내 질문을 최신순으로 조회하고 관리자 열람을 기록합니다.")
    @GetMapping
    fun list(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Parameter(description = "0부터 시작하는 페이지 번호.") @RequestParam(defaultValue = "0") page: Int,
        @Parameter(description = "1~50개 사이의 페이지 크기.") @RequestParam(defaultValue = "20") size: Int,
    ): ResponseEntity<AiAdminHistoryPage>

    @Operation(summary = "AI 목자 질문·답변 상세 열람", description = "관리자가 열람 사유를 기록하고 질문과 답변 버전을 확인합니다.")
    @PostMapping("/{turnId}/review")
    fun detail(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Parameter(description = "질문 또는 개선 답변의 턴 UUID.") @PathVariable turnId: UUID,
        @RequestBody request: AiAdminHistoryReviewRequest,
    ): ResponseEntity<AiAdminHistoryDetail>
}
