package com.example.mypasture.ai.presentation

import com.example.mypasture.ai.application.AiRuntimeConfigService
import com.example.mypasture.ai.domain.AiUseCase
import com.example.mypasture.auth.infrastructure.JwtPrincipal
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** ADMIN 요청을 AI 런타임 설정 application service에 전달합니다. */
@RestController
@RequestMapping("/api/v1/admin/ai")
class AiRuntimeConfigAdminController(
    private val service: AiRuntimeConfigService,
) : AiRuntimeConfigAdminApi {
    override fun overview(useCase: AiUseCase): AiRuntimeConfigOverviewResponse = AiRuntimeConfigOverviewResponse(
        current = service.current(useCase).toResponse(),
        models = service.allowedModels().map { it.toResponse() },
        history = service.history(useCase).map { it.toResponse() },
    )

    override fun activate(
        principal: JwtPrincipal,
        request: ActivateAiRuntimeConfigRequest,
    ): AiRuntimeConfigVersionResponse =
        service.activate(principal.userId, request.operationId, request.toCommand()).toResponse()

    override fun rollback(
        principal: JwtPrincipal,
        request: RollbackAiRuntimeConfigRequest,
    ): AiRuntimeConfigVersionResponse =
        service.rollback(principal.userId, request.operationId, request.targetVersionId, request.reason).toResponse()
}
