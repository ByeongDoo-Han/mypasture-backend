package com.example.mypasture.user.infrastructure

import com.example.mypasture.user.domain.UserAvatarPlatformEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/** 계정별 양 발판 선택을 읽고 저장하는 저장소입니다. */
interface UserAvatarPlatformRepository : JpaRepository<UserAvatarPlatformEntity, UUID>
