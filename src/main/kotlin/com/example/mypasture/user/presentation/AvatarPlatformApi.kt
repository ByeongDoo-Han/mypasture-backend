package com.example.mypasture.user.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody

/** 본인의 양 발판 선택과 기본 아이템 목록을 제공하는 HTTP 계약입니다. */
interface AvatarPlatformApi {
    @GetMapping
    fun mine(@AuthenticationPrincipal principal: JwtPrincipal): AvatarPlatformResponse

    @PutMapping
    fun select(@AuthenticationPrincipal principal: JwtPrincipal, @Valid @RequestBody request: SelectAvatarPlatformRequest): AvatarPlatformResponse
}

/** 선택할 기본 제공 발판 코드입니다. */
data class SelectAvatarPlatformRequest(@field:NotBlank val itemCode: String)

/** 선택 상태와 사용 가능한 발판 아이템을 반환합니다. */
data class AvatarPlatformResponse(val selectedItemCode: String, val items: List<AvatarPlatformItemResponse>)

/** 아직 구매 없이 누구나 사용할 수 있는 발판 아이템 정보입니다. */
data class AvatarPlatformItemResponse(val code: String, val title: String, val description: String, val owned: Boolean)
