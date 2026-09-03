package com.example.mypasture.policy.application

import com.example.mypasture.policy.domain.PolicyDocument
import com.example.mypasture.policy.domain.PolicyRequirement
import com.example.mypasture.policy.domain.PolicyScope
import com.example.mypasture.policy.domain.PolicyType
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.time.LocalDate

/** 배포물에 포함된 한국어 정책 원문과 현재 버전을 단일 목록으로 관리합니다. */
@Component
class PolicyCatalog(resourceLoader: ResourceLoader) {
    private val documents = listOf(
        definition(PolicyType.TERMS_OF_SERVICE, "서비스 이용약관", "서비스 이용 조건과 이용자의 권리·의무", PolicyScope.ACCOUNT, PolicyRequirement.REQUIRED, "terms-of-service-v1.0.0.md"),
        definition(PolicyType.PRIVACY_POLICY, "개인정보 처리방침", "개인정보 처리 목적, 보유기간과 이용자의 권리", PolicyScope.ACCOUNT, PolicyRequirement.NOTICE_ONLY, "privacy-policy-v1.0.0.md"),
        definition(PolicyType.PRIVACY_COLLECTION, "개인정보 수집·이용 동의", "계정 생성과 서비스 제공에 필요한 개인정보", PolicyScope.ACCOUNT, PolicyRequirement.REQUIRED, "privacy-collection-v1.0.0.md"),
        definition(PolicyType.AI_SENSITIVE_DATA, "AI 목자 민감정보 처리 동의", "신앙, 감정, 기도 내용을 활용한 AI 응답 제공", PolicyScope.AI, PolicyRequirement.REQUIRED, "ai-sensitive-data-v1.0.0.md"),
        definition(PolicyType.MARKETING_NOTIFICATIONS, "혜택·소식 알림 동의", "이벤트와 신규 기능 안내 수신", PolicyScope.MARKETING, PolicyRequirement.OPTIONAL, "marketing-notifications-v1.0.0.md"),
    ).map { it.copy(content = resourceLoader.getResource("classpath:policies/ko/${it.content}").inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader -> reader.readText() }) }

    fun current(scope: PolicyScope?): List<PolicyDocument> = documents.filter { scope == null || it.scope == scope }

    fun find(type: PolicyType, version: String): PolicyDocument =
        documents.firstOrNull { it.type == type && it.version == version } ?: throw PolicyNotFoundException()

    private fun definition(type: PolicyType, title: String, summary: String, scope: PolicyScope, requirement: PolicyRequirement, path: String) =
        PolicyDocument(type, VERSION, title, summary, scope, requirement, EFFECTIVE_DATE, path)

    companion object {
        const val VERSION = "1.0.0"
        val EFFECTIVE_DATE: LocalDate = LocalDate.of(2026, 8, 31)
    }
}

class PolicyNotFoundException : RuntimeException("Policy document was not found")
class RequiredPolicyConsentException : RuntimeException("Current required policy consent is missing")
class RequiredPolicyWithdrawalException : RuntimeException("Required policy consent cannot be withdrawn")
