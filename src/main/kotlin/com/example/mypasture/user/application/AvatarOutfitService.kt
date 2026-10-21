package com.example.mypasture.user.application

import com.example.mypasture.auth.infrastructure.UserRepository
import com.example.mypasture.user.domain.AvatarEquipmentItem
import com.example.mypasture.user.domain.AvatarSlot
import com.example.mypasture.user.domain.UserAvatarOutfitEntity
import com.example.mypasture.user.infrastructure.UserAvatarOutfitRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** 본인의 무료 양 아이템 장착 상태만 조회·갱신합니다. */
@Service
class AvatarOutfitService(
    private val users: UserRepository,
    private val outfits: UserAvatarOutfitRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional(readOnly = true)
    fun mine(userId: UUID): Map<AvatarSlot, String> {
        checkNotNull(users.findByIdAndDeletedAtIsNull(userId)) { "Active user is required" }
        return outfits.findById(userId).orElse(null)?.equipped() ?: emptyMap()
    }

    @Transactional
    fun equip(userId: UUID, slotCode: String, itemCode: String?): Map<AvatarSlot, String> {
        val slot = AvatarSlot.entries.firstOrNull { it.name == slotCode }
            ?: throw IllegalArgumentException("Unknown avatar slot")
        if (itemCode != null && AvatarEquipmentItem.entries.none { it.name == itemCode && it.slot == slot }) {
            throw IllegalArgumentException("Unknown or unavailable avatar item for slot")
        }
        // 사용자 행 잠금은 첫 장착 동시 요청도 직렬화하고 PK가 중복 상태를 막습니다.
        checkNotNull(users.findActiveByIdForUpdate(userId)) { "Active user is required" }
        val outfit = outfits.findById(userId).orElseGet { UserAvatarOutfitEntity(userId = userId) }
        outfit.equip(slot, itemCode)
        outfit.updatedAt = Instant.now(clock)
        outfits.save(outfit)
        return outfit.equipped()
    }
}
