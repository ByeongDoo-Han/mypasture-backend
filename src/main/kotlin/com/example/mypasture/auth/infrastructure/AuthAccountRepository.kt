package com.example.mypasture.auth.infrastructure

import com.example.mypasture.auth.domain.AuthAccountEntity
import com.example.mypasture.auth.domain.AuthProvider
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

/** 로그인 제공자별 계정 정보를 조회하고 저장하는 JPA repository입니다. */
@Repository
interface AuthAccountRepository : JpaRepository<AuthAccountEntity, UUID> {
    fun existsByProviderAndProviderUserId(
        provider: AuthProvider,
        providerUserId: String,
    ): Boolean

    fun findByProviderAndProviderUserId(
        provider: AuthProvider,
        providerUserId: String,
    ): AuthAccountEntity?

    fun findByUserIdAndProvider(userId: UUID, provider: AuthProvider): AuthAccountEntity?

    fun findAllByUserId(userId: UUID): List<AuthAccountEntity>

    fun deleteAllByUserId(userId: UUID)
}
