package com.example.mypasture.coupon.infrastructure

import com.example.mypasture.coupon.domain.CouponCampaignEntity
import com.example.mypasture.coupon.domain.CouponCampaignStatus
import com.example.mypasture.coupon.domain.CouponCodeEntity
import com.example.mypasture.coupon.domain.CouponRedemptionEntity
import com.example.mypasture.coupon.domain.UserCollectibleEntity
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface CouponCampaignRepository : JpaRepository<CouponCampaignEntity, UUID> {
    fun findAllByStatus(status: CouponCampaignStatus, pageable: Pageable): Page<CouponCampaignEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select campaign from CouponCampaignEntity campaign where campaign.id = :id")
    fun findByIdForUpdate(@Param("id") id: UUID): CouponCampaignEntity?
}

@Repository
interface CouponCodeRepository : JpaRepository<CouponCodeEntity, UUID> {
    fun findByCodeHash(codeHash: String): CouponCodeEntity?
    fun findAllByCampaignId(campaignId: UUID): List<CouponCodeEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select code from CouponCodeEntity code where code.id = :id")
    fun findByIdForUpdate(@Param("id") id: UUID): CouponCodeEntity?
}

@Repository
interface CouponRedemptionRepository : JpaRepository<CouponRedemptionEntity, UUID> {
    fun existsByUserIdAndEligibilityKey(userId: UUID, eligibilityKey: String): Boolean
    fun countByCampaignId(campaignId: UUID): Long
}

@Repository
interface UserCollectibleRepository : JpaRepository<UserCollectibleEntity, UUID> {
    fun findByUserIdAndItemCode(userId: UUID, itemCode: String): UserCollectibleEntity?
    fun findAllByUserIdOrderByUpdatedAtDesc(userId: UUID): List<UserCollectibleEntity>
}
