package com.example.mypasture.policy.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.policy.domain.PolicyScope
import com.example.mypasture.policy.domain.PolicyType
import com.example.mypasture.policy.presentation.dto.PolicyConsentStatusResponse
import com.example.mypasture.policy.presentation.dto.PolicyConsentUpdateRequest
import com.example.mypasture.policy.presentation.dto.PolicyDocumentResponse
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam

/** Swagger 문서와 controller 구현을 분리한 정책·동의 REST API contract입니다. */
interface PolicyApi {
    @GetMapping("/current")
    fun current(@RequestParam(required = false) scope: PolicyScope?): List<PolicyDocumentResponse>

    @GetMapping("/{type}/{version}")
    fun document(@PathVariable type: PolicyType, @PathVariable version: String): PolicyDocumentResponse

    @GetMapping("/consent-status")
    fun status(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @RequestParam(required = false) scopes: Set<PolicyScope>?,
    ): PolicyConsentStatusResponse

    @PostMapping("/consents")
    fun update(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Valid @RequestBody request: PolicyConsentUpdateRequest,
    ): PolicyConsentStatusResponse
}
