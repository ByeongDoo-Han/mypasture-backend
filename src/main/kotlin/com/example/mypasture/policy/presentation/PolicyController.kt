package com.example.mypasture.policy.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.policy.application.PolicyConsentService
import com.example.mypasture.policy.domain.PolicyScope
import com.example.mypasture.policy.domain.PolicyType
import com.example.mypasture.policy.presentation.dto.PolicyConsentUpdateRequest
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 정책 원문과 사용자별 동의 상태를 HTTP API로 제공합니다. */
@RestController
@RequestMapping("/api/v1/policies")
class PolicyController(private val service: PolicyConsentService) : PolicyApi {
    override fun current(scope: PolicyScope?) = service.current(scope)
    override fun document(type: PolicyType, version: String) = service.document(type, version)
    override fun status(principal: JwtPrincipal, scopes: Set<PolicyScope>?) = service.status(principal.userId, scopes.orEmpty())
    override fun update(principal: JwtPrincipal, request: PolicyConsentUpdateRequest) = service.update(principal.userId, request.consents, "AUTHENTICATED_API")
}
