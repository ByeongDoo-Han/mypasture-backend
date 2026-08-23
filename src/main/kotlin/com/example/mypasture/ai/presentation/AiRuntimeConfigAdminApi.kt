package com.example.mypasture.ai.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody

/** Swagger 계약과 관리자 AI 모델 운영 HTTP interface를 분리합니다. */
interface AiRuntimeConfigAdminApi {
    @GetMapping("/runtime-config")
    fun overview(): AiRuntimeConfigOverviewResponse

    @PostMapping("/runtime-config/activate")
    fun activate(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Valid @RequestBody request: ActivateAiRuntimeConfigRequest,
    ): AiRuntimeConfigVersionResponse

    @PostMapping("/runtime-config/rollback")
    fun rollback(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Valid @RequestBody request: RollbackAiRuntimeConfigRequest,
    ): AiRuntimeConfigVersionResponse
}
