package com.example.mypasture.policy.domain

import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** 정책 문서의 안정적인 종류 식별자입니다. */
enum class PolicyType { TERMS_OF_SERVICE, PRIVACY_POLICY, PRIVACY_COLLECTION, AI_SENSITIVE_DATA, MARKETING_NOTIFICATIONS }

/** 동의를 확인해야 하는 제품 기능 범위입니다. */
enum class PolicyScope { ACCOUNT, AI, MARKETING }

/** 필수 동의, 선택 동의, 단순 고지를 구분합니다. */
enum class PolicyRequirement { REQUIRED, OPTIONAL, NOTICE_ONLY }

/** 앱과 API에 제공하는 변경 불가능한 정책 문서 버전입니다. */
data class PolicyDocument(
    val type: PolicyType,
    val version: String,
    val title: String,
    val summary: String,
    val scope: PolicyScope,
    val requirement: PolicyRequirement,
    val effectiveDate: LocalDate,
    val content: String,
)

/** 사용자별 최신 동의 상태를 빠르게 조회하는 JPA entity입니다. */
@Entity
@Table(
    name = "user_policy_consents",
    uniqueConstraints = [UniqueConstraint(columnNames = ["user_id", "policy_type"])],
)
class UserPolicyConsentEntity(
    @Id val id: UUID = UUID.randomUUID(),
    val userId: UUID,
    @Enumerated(EnumType.STRING) val policyType: PolicyType,
    var policyVersion: String,
    var agreed: Boolean,
    var source: String,
    var agreedAt: Instant?,
    var withdrawnAt: Instant?,
    val createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
)

/** 동의·철회 행위를 감사 목적으로 추가만 하는 JPA entity입니다. */
@Entity
@Table(name = "user_policy_consent_events")
class UserPolicyConsentEventEntity(
    @Id val id: UUID = UUID.randomUUID(),
    val userId: UUID,
    @Enumerated(EnumType.STRING) val policyType: PolicyType,
    val policyVersion: String,
    val action: String,
    val source: String,
    val occurredAt: Instant = Instant.now(),
)
