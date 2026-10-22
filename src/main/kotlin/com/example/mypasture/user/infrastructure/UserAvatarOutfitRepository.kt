package com.example.mypasture.user.infrastructure

import com.example.mypasture.user.domain.UserAvatarOutfitEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/** 계정별 양의 착용 상태를 읽고 저장합니다. */
interface UserAvatarOutfitRepository : JpaRepository<UserAvatarOutfitEntity, UUID>
