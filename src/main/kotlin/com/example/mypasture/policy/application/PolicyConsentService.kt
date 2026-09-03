package com.example.mypasture.policy.application

import com.example.mypasture.policy.domain.PolicyRequirement
import com.example.mypasture.policy.domain.PolicyScope
import com.example.mypasture.policy.domain.PolicyType
import com.example.mypasture.policy.domain.UserPolicyConsentEntity
import com.example.mypasture.policy.domain.UserPolicyConsentEventEntity
import com.example.mypasture.policy.infrastructure.UserPolicyConsentEventRepository
import com.example.mypasture.policy.infrastructure.UserPolicyConsentRepository
import com.example.mypasture.policy.presentation.dto.PolicyConsentInput
import com.example.mypasture.policy.presentation.dto.PolicyConsentStatusResponse
import com.example.mypasture.policy.presentation.dto.PolicyDocumentResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/** 정책 문서 조회, 필수 동의 검증, 최신 상태와 감사 이력을 함께 관리합니다. */
@Service
class PolicyConsentService(
    private val catalog: PolicyCatalog,
    private val consentRepository: UserPolicyConsentRepository,
    private val eventRepository: UserPolicyConsentEventRepository,
    @Value("\${app.policy.enforcement-enabled:true}") private val enforcementEnabled: Boolean,
) {
    fun current(scope: PolicyScope?) = catalog.current(scope).map(PolicyDocumentResponse::from)

    fun document(type: PolicyType, version: String) = PolicyDocumentResponse.from(catalog.find(type, version))

    fun validateSignup(consents: List<PolicyConsentInput>) {
        if (!enforcementEnabled) return
        validateRequired(PolicyScope.ACCOUNT, consents)
    }

    /** 민감 기능 처리 직전에 서버에서도 현재 필수 동의를 검증해 클라이언트 우회를 막습니다. */
    @Transactional(readOnly = true)
    fun requireCurrent(userId: UUID, scopes: Set<PolicyScope>) {
        if (!enforcementEnabled) return
        if (!status(userId, scopes).allRequiredAgreed) throw RequiredPolicyConsentException()
    }

    @Transactional
    fun recordSignup(userId: UUID, consents: List<PolicyConsentInput>) {
        if (!enforcementEnabled) return
        save(userId, consents, "SIGNUP")
    }

    @Transactional(readOnly = true)
    fun status(userId: UUID, scopes: Set<PolicyScope>): PolicyConsentStatusResponse {
        val documents = catalog.current(null).filter { scopes.isEmpty() || it.scope in scopes }
        val saved = consentRepository.findAllByUserId(userId).associateBy { it.policyType }
        val items = documents.map { document ->
            val consent = saved[document.type]
            PolicyConsentStatusResponse.Item(
                document = PolicyDocumentResponse.from(document),
                agreed = consent?.agreed == true && consent.policyVersion == document.version,
                agreedAt = consent?.agreedAt,
            )
        }
        return PolicyConsentStatusResponse(items, items.none { it.document.requirement == PolicyRequirement.REQUIRED && !it.agreed })
    }

    @Transactional
    fun update(userId: UUID, consents: List<PolicyConsentInput>, source: String): PolicyConsentStatusResponse {
        save(userId, consents, source.take(30))
        return status(userId, emptySet())
    }

    private fun validateRequired(scope: PolicyScope, consents: List<PolicyConsentInput>) {
        val submitted = consents.associateBy { it.type }
        val missing = catalog.current(scope).filter { it.requirement == PolicyRequirement.REQUIRED }.any {
            submitted[it.type]?.let { input -> input.agreed && input.version == it.version } != true
        }
        if (missing) throw RequiredPolicyConsentException()
    }

    private fun save(userId: UUID, inputs: List<PolicyConsentInput>, source: String) {
        val now = Instant.now()
        inputs.distinctBy { it.type }.forEach { input ->
            val document = catalog.find(input.type, input.version)
            if (!input.agreed && document.requirement == PolicyRequirement.REQUIRED) throw RequiredPolicyWithdrawalException()
            if (document.requirement == PolicyRequirement.NOTICE_ONLY) return@forEach
            val current = consentRepository.findByUserIdAndPolicyType(userId, input.type)
            val entity = current?.apply {
                policyVersion = input.version
                agreed = input.agreed
                this.source = source
                agreedAt = if (input.agreed) now else agreedAt
                withdrawnAt = if (input.agreed) null else now
                updatedAt = now
            } ?: UserPolicyConsentEntity(
                userId = userId,
                policyType = input.type,
                policyVersion = input.version,
                agreed = input.agreed,
                source = source,
                agreedAt = now.takeIf { input.agreed },
                withdrawnAt = now.takeUnless { input.agreed },
            )
            consentRepository.save(entity)
            eventRepository.save(UserPolicyConsentEventEntity(userId = userId, policyType = input.type, policyVersion = input.version, action = if (input.agreed) "AGREED" else "WITHDRAWN", source = source, occurredAt = now))
        }
    }
}
