package com.example.mypasture.auth.presentation

import com.example.mypasture.auth.presentation.dto.AdminEmailLoginConfirmRequest
import com.example.mypasture.auth.presentation.dto.AdminEmailLoginRequest
import com.example.mypasture.auth.presentation.dto.AdminEmailLoginRequestedResponse
import com.example.mypasture.auth.presentation.dto.UserLoginResponse
import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody

/** 관리자 이메일 OTP의 공개 요청과 확인 HTTP 계약을 정의합니다. */
@Tag(name = "관리자 이메일 로그인", description = "관리자 전용 이메일 인증 코드를 요청하고 확인합니다.")
interface AdminEmailLoginApi {
    @Operation(summary = "관리자 로그인 코드 요청", description = "관리자 이메일로 일회용 로그인 코드를 발송하도록 요청합니다. 계정 존재 여부를 노출하지 않습니다.")
    @PostMapping("/requests")
    fun request(@Valid @RequestBody request: AdminEmailLoginRequest): ResponseEntity<AdminEmailLoginRequestedResponse>

    @Operation(summary = "관리자 로그인 코드 확인", description = "일회용 코드 검증 후 관리자 로그인 결과를 반환합니다.")
    @PostMapping("/confirm")
    fun confirm(@Valid @RequestBody request: AdminEmailLoginConfirmRequest): ResponseEntity<UserLoginResponse>
}
