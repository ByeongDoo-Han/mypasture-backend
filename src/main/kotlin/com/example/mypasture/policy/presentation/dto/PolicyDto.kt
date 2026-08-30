package com.example.mypasture.policy.presentation.dto

import com.example.mypasture.policy.domain.PolicyDocument
import com.example.mypasture.policy.domain.PolicyRequirement
import com.example.mypasture.policy.domain.PolicyScope
import com.example.mypasture.policy.domain.PolicyType
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate

/** 특정 정책 버전에 대한 사용자의 명시적 선택입니다. */
data class PolicyConsentInput(
    val type: PolicyType,
    @field:NotBlank @field:Size(max = 20) val version: String,
    val agreed: Boolean,
)

/** 로그인 사용자의 정책 동의 변경 요청입니다. */
data class PolicyConsentUpdateRequest(
    @field:Valid @field:Size(min = 1, max = 10) val consents: List<PolicyConsentInput>,
)

/** 문서 목록과 상세 조회에 공통으로 사용하는 정책 응답입니다. */
data class PolicyDocumentResponse(
    val type: PolicyType,
    val version: String,
    val title: String,
    val summary: String,
    val scope: PolicyScope,
    val requirement: PolicyRequirement,
    val effectiveDate: LocalDate,
    val content: String,
) {
    companion object {
        fun from(document: PolicyDocument) = PolicyDocumentResponse(
            document.type,
            document.version,
            document.title,
            document.summary,
            document.scope,
            document.requirement,
            document.effectiveDate,
            document.content,
        )
    }
}

/** 현재 문서별 동의 여부와 요청 범위의 필수 동의 충족 여부입니다. */
data class PolicyConsentStatusResponse(
    val items: List<Item>,
    val allRequiredAgreed: Boolean,
) {
    data class Item(
        val document: PolicyDocumentResponse,
        val agreed: Boolean,
        val agreedAt: Instant?,
    )
}
