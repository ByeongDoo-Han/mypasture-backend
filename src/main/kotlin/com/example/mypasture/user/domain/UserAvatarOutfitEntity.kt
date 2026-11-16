package com.example.mypasture.user.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** 개인 양의 독립적인 착용 부위입니다. */
enum class AvatarSlot { HAT, GLASSES, TOP, BOTTOM, SHOES, NECKLACE, BRACELET, BACK_ATTACHMENT, FACE_DECORATION, EXPRESSION }

/** 현재 모두 무료로 제공하는 양 꾸미기 아이템 계약입니다. */
enum class AvatarEquipmentItem(val slot: AvatarSlot, val title: String) {
    STRAW_HAT(AvatarSlot.HAT, "밀짚모자"),
    GREEN_BERET(AvatarSlot.HAT, "초록 베레모"),
    ROUND_GLASSES(AvatarSlot.GLASSES, "동그란 안경"),
    BLUE_VEST(AvatarSlot.TOP, "파란 조끼"),
    GREEN_VEST(AvatarSlot.TOP, "초록 조끼"),
    TAN_ZIP_JACKET(AvatarSlot.TOP, "브라운 집업 재킷"),
    TAN_PANTS(AvatarSlot.BOTTOM, "베이지 바지"),
    BROWN_BOOTS(AvatarSlot.SHOES, "갈색 장화"),
    BELL_NECKLACE(AvatarSlot.NECKLACE, "방울 목걸이"),
    GOLD_BRACELET(AvatarSlot.BRACELET, "금색 팔찌"),
    BROWN_BACKPACK(AvatarSlot.BACK_ATTACHMENT, "갈색 배낭"),
    WHITE_WINGS(AvatarSlot.BACK_ATTACHMENT, "하얀 날개"),
    FLOWER_CHEEK(AvatarSlot.FACE_DECORATION, "꽃 볼 장식"),
    HEART_EYES(AvatarSlot.EXPRESSION, "하트 눈"),
}

/** 착용 상태를 계정별 한 행으로 저장해 부위별 동시 선택을 직렬화합니다. */
@Entity
@Table(name = "user_avatar_outfits")
class UserAvatarOutfitEntity(
    @Id @Column(name = "user_id") val userId: UUID,
    @Column(name = "hat_code") var hatCode: String? = null,
    @Column(name = "glasses_code") var glassesCode: String? = null,
    @Column(name = "top_code") var topCode: String? = null,
    @Column(name = "bottom_code") var bottomCode: String? = null,
    @Column(name = "shoes_code") var shoesCode: String? = null,
    @Column(name = "necklace_code") var necklaceCode: String? = null,
    @Column(name = "bracelet_code") var braceletCode: String? = null,
    @Column(name = "back_attachment_code") var backAttachmentCode: String? = null,
    @Column(name = "face_decoration_code") var faceDecorationCode: String? = null,
    @Column(name = "expression_code") var expressionCode: String? = null,
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now(),
) {
    fun equipped(): Map<AvatarSlot, String> = buildMap {
        hatCode?.let { put(AvatarSlot.HAT, it) }
        glassesCode?.let { put(AvatarSlot.GLASSES, it) }
        topCode?.let { put(AvatarSlot.TOP, it) }
        bottomCode?.let { put(AvatarSlot.BOTTOM, it) }
        shoesCode?.let { put(AvatarSlot.SHOES, it) }
        necklaceCode?.let { put(AvatarSlot.NECKLACE, it) }
        braceletCode?.let { put(AvatarSlot.BRACELET, it) }
        backAttachmentCode?.let { put(AvatarSlot.BACK_ATTACHMENT, it) }
        faceDecorationCode?.let { put(AvatarSlot.FACE_DECORATION, it) }
        expressionCode?.let { put(AvatarSlot.EXPRESSION, it) }
    }

    fun equip(slot: AvatarSlot, code: String?) {
        when (slot) {
            AvatarSlot.HAT -> hatCode = code
            AvatarSlot.GLASSES -> glassesCode = code
            AvatarSlot.TOP -> topCode = code
            AvatarSlot.BOTTOM -> bottomCode = code
            AvatarSlot.SHOES -> shoesCode = code
            AvatarSlot.NECKLACE -> necklaceCode = code
            AvatarSlot.BRACELET -> braceletCode = code
            AvatarSlot.BACK_ATTACHMENT -> backAttachmentCode = code
            AvatarSlot.FACE_DECORATION -> faceDecorationCode = code
            AvatarSlot.EXPRESSION -> expressionCode = code
        }
    }
}
