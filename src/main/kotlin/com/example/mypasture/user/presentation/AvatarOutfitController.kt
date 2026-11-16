package com.example.mypasture.user.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.common.error.ApiErrorResponse
import com.example.mypasture.common.error.CommonErrorCode
import com.example.mypasture.user.application.AvatarOutfitService
import com.example.mypasture.user.domain.AvatarEquipmentItem
import com.example.mypasture.user.domain.AvatarSlot
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 인증된 계정의 양 꾸미기 요청만 처리합니다. */
@RestController
@RequestMapping("/api/v1/avatar/outfit")
class AvatarOutfitController(private val service: AvatarOutfitService) : AvatarOutfitApi {
    override fun mine(principal: JwtPrincipal) = service.mine(principal.userId).toResponse()

    override fun equip(principal: JwtPrincipal, request: EquipAvatarItemRequest) =
        service.equip(principal.userId, request.slot, request.itemCode).toResponse()

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalidItem(request: HttpServletRequest): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.badRequest().body(ApiErrorResponse.of(CommonErrorCode.INVALID_REQUEST, request))

    private fun Map<AvatarSlot, String>.toResponse() = AvatarOutfitResponse(
        equipped = entries.associate { it.key.name to it.value },
        items = AvatarEquipmentItem.entries.map { AvatarOutfitItemResponse(it.name, it.slot.name, it.title, true) },
    )
}
