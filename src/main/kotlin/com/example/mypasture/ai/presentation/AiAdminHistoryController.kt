package com.example.mypasture.ai.presentation

import com.example.mypasture.ai.application.AiAdminHistoryService
import com.example.mypasture.auth.infrastructure.JwtPrincipal
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** 서버에서 ADMIN 권한을 검증한 뒤 민감한 응답을 캐시 없이 전달합니다. */
@RestController
@RequestMapping("/api/v1/admin/ai/history")
class AiAdminHistoryController(private val service: AiAdminHistoryService) : AiAdminHistoryApi {
    override fun list(principal: JwtPrincipal, page: Int, size: Int) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(principal.userId, page, size))

    override fun detail(principal: JwtPrincipal, turnId: UUID, request: AiAdminHistoryReviewRequest) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.detail(principal.userId, turnId, request.reason))
}
