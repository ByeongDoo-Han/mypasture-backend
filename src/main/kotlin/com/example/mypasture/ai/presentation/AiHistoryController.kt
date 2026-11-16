package com.example.mypasture.ai.presentation

import com.example.mypasture.ai.application.AiAnswerHistoryService
import com.example.mypasture.auth.infrastructure.JwtPrincipal
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** JWT 사용자 소유권을 history service에 전달하고 민감한 응답의 캐시를 금지합니다. */
@RestController
@RequestMapping("/api/v1/ai")
class AiHistoryController(private val service: AiAnswerHistoryService) : AiHistoryApi {
    override fun list(principal: JwtPrincipal, size: Int, cursor: String?) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(principal.userId, size, cursor))

    override fun detail(principal: JwtPrincipal, turnId: UUID) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.detail(principal.userId, turnId))

    override fun delete(principal: JwtPrincipal, turnId: UUID): ResponseEntity<Void> {
        service.delete(principal.userId, turnId)
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
    }
}
