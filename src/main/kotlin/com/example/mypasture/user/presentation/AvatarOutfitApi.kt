package com.example.mypasture.user.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody

/** 내 양의 부위별 장착 상태를 제공하는 HTTP 계약입니다. */
@Tag(name = "양 캐릭터 꾸미기", description = "내 양의 기본 제공 아이템을 부위별로 장착하거나 해제합니다.")
interface AvatarOutfitApi {
    @Operation(summary = "내 양 착용 상태 조회", description = "장착한 아이템과 현재 무료로 제공하는 아이템 목록을 반환합니다.")
    @GetMapping
    fun mine(@AuthenticationPrincipal principal: JwtPrincipal): AvatarOutfitResponse

    @Operation(summary = "내 양 아이템 장착 또는 해제", description = "slot에 해당하는 itemCode를 장착합니다. 예: TOP과 TAN_ZIP_JACKET. null이면 해당 부위를 해제합니다.")
    @PutMapping
    fun equip(@AuthenticationPrincipal principal: JwtPrincipal, @Valid @RequestBody request: EquipAvatarItemRequest): AvatarOutfitResponse
}

/** null 아이템 코드는 선택 부위의 장착 해제를 뜻합니다. */
data class EquipAvatarItemRequest(@field:NotBlank val slot: String, val itemCode: String?)

/** 현재 장착 상태와 기본 제공 아이템 카탈로그입니다. */
data class AvatarOutfitResponse(val equipped: Map<String, String>, val items: List<AvatarOutfitItemResponse>)

/** 계정에 관계없이 이용할 수 있는 꾸미기 아이템입니다. */
data class AvatarOutfitItemResponse(val code: String, val slot: String, val title: String, val owned: Boolean)
