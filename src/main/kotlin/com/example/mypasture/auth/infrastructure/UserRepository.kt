package com.example.mypasture.auth.infrastructure

import com.example.mypasture.user.domain.UserEntity
import com.example.mypasture.user.domain.UserRole
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

/** 회원가입/로그인에서 사용자 계정을 조회하고 저장하는 JPA repository입니다. */
@Repository
interface UserRepository : JpaRepository<UserEntity, UUID> {
    fun existsByEmailAndDeletedAtIsNull(email: String): Boolean

    fun findByEmailAndDeletedAtIsNull(email: String): UserEntity?

    fun findByIdAndDeletedAtIsNull(id: UUID): UserEntity?

    fun findAllByRoleAndDeletedAtIsNull(role: UserRole): List<UserEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select user from UserEntity user where user.email = :email and user.deletedAt is null")
    fun findByEmailForUpdate(@Param("email") email: String): UserEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select user from UserEntity user where user.id = :id and user.deletedAt is null")
    fun findActiveByIdForUpdate(@Param("id") id: UUID): UserEntity?
}
