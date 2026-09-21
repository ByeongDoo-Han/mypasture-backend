package com.example.mypasture.auth.presentation.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.util.UUID

/** 관리자 계정에 일회용 이메일 코드를 요청합니다. */
data class AdminEmailLoginRequest(
    @field:NotBlank @field:Email @field:Size(max = 320) val email: String,
)

/** 자격·발송 여부와 관계없이 동일한 형태로 반환하는 challenge 응답입니다. */
data class AdminEmailLoginRequestedResponse(
    val challengeId: UUID,
    val expiresInSeconds: Long = 300,
    val resendAfterSeconds: Long = 60,
)

/** 이메일에서 받은 여섯 자리 코드를 해당 challenge에 한 번만 제출합니다. */
data class AdminEmailLoginConfirmRequest(
    val challengeId: UUID,
    @field:Pattern(regexp = "^[0-9]{6}$") val code: String,
)
