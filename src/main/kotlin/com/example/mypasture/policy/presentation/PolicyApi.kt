package com.example.mypasture.policy.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.policy.domain.PolicyScope
import com.example.mypasture.policy.domain.PolicyType
import com.example.mypasture.policy.presentation.dto.PolicyConsentStatusResponse
import com.example.mypasture.policy.presentation.dto.PolicyConsentUpdateRequest
import com.example.mypasture.policy.presentation.dto.PolicyDocumentResponse
import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import jakarta.validation.Valid
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam

/** Swagger 문서와 controller 구현을 분리한 정책·동의 REST API contract입니다. */
@Tag(name = "정책 · 동의", description = "공개 정책 문서와 계정별 동의 상태를 관리합니다.")
interface PolicyApi {
    @Operation(summary = "현재 정책 문서 목록", description = "지정한 범위에 현재 적용되는 정책 문서와 버전을 조회합니다.")
    @GetMapping("/current")
    fun current(@Parameter(description = "현재 정책 문서를 조회할 적용 범위.") @RequestParam(required = false) scope: PolicyScope?): List<PolicyDocumentResponse>

    @Operation(summary = "정책 문서 조회", description = "정책 종류와 버전으로 공개 정책 문서를 조회합니다.")
    @GetMapping("/{type}/{version}")
    fun document(@Parameter(description = "조회할 정책 문서 종류.") @PathVariable type: PolicyType, @Parameter(description = "조회할 정책 문서 버전.") @PathVariable version: String): PolicyDocumentResponse

    @Operation(summary = "내 정책 동의 상태", description = "로그인한 사용자가 지정 범위의 필수 정책에 동의했는지 조회합니다.")
    @GetMapping("/consent-status")
    fun status(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Parameter(description = "동의 상태를 조회할 정책 범위 목록.") @RequestParam(required = false) scopes: Set<PolicyScope>?,
    ): PolicyConsentStatusResponse

    @Operation(summary = "정책 동의 갱신", description = "로그인한 사용자의 정책 동의를 저장합니다.")
    @PostMapping("/consents")
    fun update(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Valid @RequestBody request: PolicyConsentUpdateRequest,
    ): PolicyConsentStatusResponse
}
