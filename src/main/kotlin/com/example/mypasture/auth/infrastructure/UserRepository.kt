package com.example.mypasture.auth.infrastructure

import com.example.mypasture.user.domain.UserEntity
import com.example.mypasture.user.domain.UserRole
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

/** 회원가입/로그인에서 사용자 계정을 조회하고 저장하는 JPA repository입니다. */
@Repository
interface UserRepository : JpaRepository<UserEntity, UUID> {
    @Query("select count(user) > 0 from UserEntity user where user.deletedAt is null and lower(user.displayName) = :nicknameKey")
    fun existsActiveDisplayName(@Param("nicknameKey") nicknameKey: String): Boolean

    @Query("select count(user) > 0 from UserEntity user where user.deletedAt is null and lower(user.displayName) = :nicknameKey and user.id <> :excludedId")
    fun existsActiveDisplayNameExcept(@Param("nicknameKey") nicknameKey: String, @Param("excludedId") excludedId: UUID): Boolean

    fun existsByEmailAndDeletedAtIsNull(email: String): Boolean

    fun findByEmailAndDeletedAtIsNull(email: String): UserEntity?

    fun findByIdAndDeletedAtIsNull(id: UUID): UserEntity?

    @Query("select user from UserEntity user where user.deletedAt is null and lower(user.displayName) like lower(concat('%', :query, '%')) order by user.displayName")
    fun searchActiveDisplayNames(@Param("query") query: String, pageable: Pageable): List<UserEntity>

    fun findAllByRoleAndDeletedAtIsNull(role: UserRole): List<UserEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select user from UserEntity user where user.email = :email and user.deletedAt is null")
    fun findByEmailForUpdate(@Param("email") email: String): UserEntity?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select user from UserEntity user where user.id = :id and user.deletedAt is null")
    fun findActiveByIdForUpdate(@Param("id") id: UUID): UserEntity?
}
