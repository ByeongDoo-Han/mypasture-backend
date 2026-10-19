package com.example.mypasture.ai.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.ai.domain.AiUseCase
import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam

/** Swagger 계약과 관리자 AI 모델 운영 HTTP interface를 분리합니다. */
@Tag(name = "관리자 · AI 운영 설정", description = "AI 모델 운영 설정의 조회와 전환을 관리합니다.")
interface AiRuntimeConfigAdminApi {
    @Operation(summary = "AI 운영 설정 조회", description = "관리자가 용도별 활성 모델 설정과 변경 이력을 조회합니다.")
    @GetMapping("/runtime-config")
    fun overview(
        @Parameter(description = "조회할 AI 사용 용도. 생략하면 AI_PASTOR입니다.") @RequestParam(defaultValue = "AI_PASTOR") useCase: AiUseCase,
    ): AiRuntimeConfigOverviewResponse

    @Operation(summary = "AI 운영 설정 활성화", description = "관리자가 선택한 모델 설정을 활성화하고 변경 이력을 남깁니다.")
    @PostMapping("/runtime-config/activate")
    fun activate(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Valid @RequestBody request: ActivateAiRuntimeConfigRequest,
    ): AiRuntimeConfigVersionResponse

    @Operation(summary = "AI 운영 설정 되돌리기", description = "관리자가 이전 모델 설정으로 되돌리고 변경 이력을 남깁니다.")
    @PostMapping("/runtime-config/rollback")
    fun rollback(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Valid @RequestBody request: RollbackAiRuntimeConfigRequest,
    ): AiRuntimeConfigVersionResponse
}
