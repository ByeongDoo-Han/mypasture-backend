package com.example.mypasture.auth.presentation

import com.example.mypasture.auth.presentation.dto.AdminEmailLoginConfirmRequest
import com.example.mypasture.auth.presentation.dto.AdminEmailLoginRequest
import com.example.mypasture.auth.presentation.dto.AdminEmailLoginRequestedResponse
import com.example.mypasture.auth.presentation.dto.UserLoginResponse
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody

/** 관리자 이메일 OTP의 공개 요청과 확인 HTTP 계약을 정의합니다. */
interface AdminEmailLoginApi {
    @PostMapping("/requests")
    fun request(@Valid @RequestBody request: AdminEmailLoginRequest): ResponseEntity<AdminEmailLoginRequestedResponse>

    @PostMapping("/confirm")
    fun confirm(@Valid @RequestBody request: AdminEmailLoginConfirmRequest): ResponseEntity<UserLoginResponse>
}
