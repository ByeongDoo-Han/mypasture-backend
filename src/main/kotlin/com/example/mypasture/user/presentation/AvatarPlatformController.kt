package com.example.mypasture.user.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.common.error.ApiErrorResponse
import com.example.mypasture.common.error.CommonErrorCode
import com.example.mypasture.user.application.AvatarPlatformSelection
import com.example.mypasture.user.application.AvatarPlatformService
import com.example.mypasture.user.domain.AvatarPlatformItem
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** JWT 소유자의 발판 선택만 조회·변경하고 HTTP 응답으로 변환합니다. */
@RestController
@RequestMapping("/api/v1/avatar/platform")
class AvatarPlatformController(private val service: AvatarPlatformService) : AvatarPlatformApi {
    override fun mine(principal: JwtPrincipal): AvatarPlatformResponse = service.mine(principal.userId).toResponse()

    override fun select(principal: JwtPrincipal, request: SelectAvatarPlatformRequest): AvatarPlatformResponse =
        service.select(principal.userId, request.itemCode).toResponse()

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalidItem(request: HttpServletRequest): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.badRequest().body(ApiErrorResponse.of(CommonErrorCode.INVALID_REQUEST, request))

    private fun AvatarPlatformSelection.toResponse(): AvatarPlatformResponse = AvatarPlatformResponse(
        selectedItemCode = selected.name,
        items = AvatarPlatformItem.entries.map { AvatarPlatformItemResponse(it.name, it.title, it.description, true) },
    )
}
