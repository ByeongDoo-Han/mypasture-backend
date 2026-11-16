package com.example.mypasture.user.application

import com.example.mypasture.auth.infrastructure.UserRepository
import com.example.mypasture.user.domain.AvatarPlatformItem
import com.example.mypasture.user.domain.UserAvatarPlatformEntity
import com.example.mypasture.user.infrastructure.UserAvatarPlatformRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** 본인에게 기본 제공된 미니 발판을 조회하고 선택을 계정에 저장합니다. */
@Service
class AvatarPlatformService(
    private val users: UserRepository,
    private val platforms: UserAvatarPlatformRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional(readOnly = true)
    fun mine(userId: UUID): AvatarPlatformSelection {
        checkNotNull(users.findByIdAndDeletedAtIsNull(userId)) { "Active user is required" }
        return AvatarPlatformSelection(platforms.findById(userId).orElse(null)?.selectedCode ?: AvatarPlatformItem.GRASS)
    }

    @Transactional
    fun select(userId: UUID, code: String): AvatarPlatformSelection {
        val item = AvatarPlatformItem.entries.firstOrNull { it.name == code }
            ?: throw IllegalArgumentException("Unknown or unavailable avatar platform")
        // 사용자 잠금이 첫 선택 동시 요청도 직렬화하고, PK가 영속적인 중복 행을 막습니다.
        checkNotNull(users.findActiveByIdForUpdate(userId)) { "Active user is required" }
        val selection = platforms.findById(userId).orElseGet { UserAvatarPlatformEntity(userId = userId) }
        selection.selectedCode = item
        selection.updatedAt = Instant.now(clock)
        platforms.save(selection)
        return AvatarPlatformSelection(item)
    }
}

/** 화면에 보여줄 현재 발판과 기본 제공 아이템 목록입니다. */
data class AvatarPlatformSelection(val selected: AvatarPlatformItem)
