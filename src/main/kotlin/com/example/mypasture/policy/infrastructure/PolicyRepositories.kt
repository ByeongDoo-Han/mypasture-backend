package com.example.mypasture.policy.infrastructure

import com.example.mypasture.policy.domain.PolicyType
import com.example.mypasture.policy.domain.UserPolicyConsentEntity
import com.example.mypasture.policy.domain.UserPolicyConsentEventEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

/** 사용자별 최신 정책 동의 상태를 영속화합니다. */
@Repository
interface UserPolicyConsentRepository : JpaRepository<UserPolicyConsentEntity, UUID> {
    fun findAllByUserId(userId: UUID): List<UserPolicyConsentEntity>
    fun findByUserIdAndPolicyType(userId: UUID, policyType: PolicyType): UserPolicyConsentEntity?
}

/** 변경 불가능한 정책 동의 감사 이벤트를 저장합니다. */
@Repository
interface UserPolicyConsentEventRepository : JpaRepository<UserPolicyConsentEventEntity, UUID>
