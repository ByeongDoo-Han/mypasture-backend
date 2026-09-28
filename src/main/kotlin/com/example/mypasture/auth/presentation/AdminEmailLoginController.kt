package com.example.mypasture.auth.presentation

import com.example.mypasture.auth.application.AdminEmailLoginService
import com.example.mypasture.auth.presentation.dto.AdminEmailLoginConfirmRequest
import com.example.mypasture.auth.presentation.dto.AdminEmailLoginRequest
import com.example.mypasture.auth.presentation.dto.AdminEmailLoginRequestedResponse
import com.example.mypasture.auth.presentation.dto.UserLoginResponse
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 관리자 이메일 인증 요청을 use case에 전달하고 인증 응답의 저장을 금지합니다. */
@RestController
@RequestMapping("/api/v1/auth/admin-email-login")
class AdminEmailLoginController(private val service: AdminEmailLoginService) : AdminEmailLoginApi {
    override fun request(request: AdminEmailLoginRequest): ResponseEntity<AdminEmailLoginRequestedResponse> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.request(request))

    override fun confirm(request: AdminEmailLoginConfirmRequest): ResponseEntity<UserLoginResponse> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.confirm(request))
}
