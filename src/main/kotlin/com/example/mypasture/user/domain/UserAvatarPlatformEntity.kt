package com.example.mypasture.user.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** 개인 양이 서 있는 미니 발판의 기본 제공 아이템입니다. */
enum class AvatarPlatformItem(val title: String, val description: String) {
    GRASS("잔디 발판", "작은 꽃이 핀 초록 잔디"),
    WOOD("나무 발판", "따뜻한 나무 데크"),
    STONE("돌 발판", "둥근 돌이 놓인 발판"),
}

/** 사용자가 현재 선택한 발판 아이템을 계정별로 저장합니다. */
@Entity
@Table(name = "user_avatar_platforms")
class UserAvatarPlatformEntity(
    @Id
    @Column(name = "user_id")
    val userId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(name = "selected_code", nullable = false)
    var selectedCode: AvatarPlatformItem = AvatarPlatformItem.GRASS,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
