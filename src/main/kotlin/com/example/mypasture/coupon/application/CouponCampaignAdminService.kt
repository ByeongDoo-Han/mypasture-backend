package com.example.mypasture.coupon.application

import com.example.mypasture.admin_audit.domain.AdminAuditAction
import com.example.mypasture.admin_audit.domain.AdminAuditLogEntity
import com.example.mypasture.admin_audit.infrastructure.AdminAuditLogRepository
import com.example.mypasture.coupon.domain.CouponCampaignEntity
import com.example.mypasture.coupon.domain.CouponCampaignStatus
import com.example.mypasture.coupon.domain.CouponCodeEntity
import com.example.mypasture.coupon.domain.CouponCodeType
import com.example.mypasture.coupon.infrastructure.CouponCampaignRepository
import com.example.mypasture.coupon.infrastructure.CouponCodeHasher
import com.example.mypasture.coupon.infrastructure.CouponCodeRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

/** 운영자가 공유·개별 쿠폰 캠페인을 발급하고 중지하는 관리 유스케이스입니다. */
@Service
class CouponCampaignAdminService(
    private val campaigns: CouponCampaignRepository,
    private val codes: CouponCodeRepository,
    private val hasher: CouponCodeHasher,
    private val audits: AdminAuditLogRepository,
) {
    @Transactional
    fun create(adminUserId: UUID, command: CreateCouponCampaignCommand, now: Instant = Instant.now()): CreatedCouponCampaign {
        validate(command, now)
        audits.findByOperationId(command.operationId)?.let { audit ->
            val existing = campaigns.findById(audit.targetId).orElseThrow { CouponCampaignNotFoundException() }
            return CreatedCouponCampaign(existing.toView(), emptyList())
        }

        val rawCodes = when (command.codeType) {
            CouponCodeType.SHARED -> listOf(command.sharedCode?.trim()?.takeIf(String::isNotBlank) ?: generateCode())
            CouponCodeType.UNIQUE -> List(command.codeCount!!) { generateCode() }
        }
        val maxRedemptions = when (command.codeType) {
            CouponCodeType.SHARED -> command.maxRedemptions!!
            CouponCodeType.UNIQUE -> rawCodes.size.toLong()
        }
        val campaign = campaigns.save(
            CouponCampaignEntity(
                name = command.name.trim(),
                eligibilityKey = command.eligibilityKey.trim().lowercase(),
                codeType = command.codeType,
                redeemStartsAt = command.redeemStartsAt,
                redeemEndsAt = command.redeemEndsAt,
                maxRedemptions = maxRedemptions,
                rewardType = command.rewardType,
                rewardItemCode = command.rewardItemCode.trim().uppercase(),
                rewardQuantity = command.rewardQuantity,
                createdBy = adminUserId,
                createdAt = now,
                updatedAt = now,
            ),
        )
        codes.saveAll(
            rawCodes.map { raw ->
                CouponCodeEntity(
                    campaignId = campaign.id,
                    codeHash = runCatching { hasher.hash(raw) }.getOrElse { throw CouponCampaignInvalidException("쿠폰 코드는 영문과 숫자 8~32자로 입력해 주세요.") },
                    codeHint = hasher.hint(raw),
                    maxRedemptions = if (command.codeType == CouponCodeType.SHARED) maxRedemptions else 1,
                    createdAt = now,
                )
            },
        )
        audits.save(
            AdminAuditLogEntity(
                operationId = command.operationId,
                adminUserId = adminUserId,
                action = AdminAuditAction.COUPON_CAMPAIGN_CREATED,
                targetType = "COUPON_CAMPAIGN",
                targetId = campaign.id,
                reason = command.reason.trim(),
                details = "codeType=${command.codeType},maxRedemptions=$maxRedemptions,rewardType=${command.rewardType},rewardItemCode=${command.rewardItemCode.trim().uppercase()},rewardQuantity=${command.rewardQuantity}",
            ),
        )
        return CreatedCouponCampaign(campaign.toView(), rawCodes.map(::formatCode))
    }

    fun list(status: CouponCampaignStatus?, page: Int, size: Int): CouponCampaignPage {
        val pageable = PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, 100))
        val result = if (status == null) campaigns.findAll(pageable) else campaigns.findAllByStatus(status, pageable)
        return CouponCampaignPage(result.content.map { it.toView() }, result.number, result.size, result.totalElements, result.totalPages)
    }

    @Transactional
    fun deactivate(id: UUID, adminUserId: UUID, operationId: UUID, reason: String, now: Instant = Instant.now()): CouponCampaignView {
        audits.findByOperationId(operationId)?.let { return campaigns.findById(it.targetId).orElseThrow { CouponCampaignNotFoundException() }.toView() }
        val campaign = campaigns.findByIdForUpdate(id) ?: throw CouponCampaignNotFoundException()
        campaign.deactivate(now)
        audits.save(
            AdminAuditLogEntity(
                operationId = operationId,
                adminUserId = adminUserId,
                action = AdminAuditAction.COUPON_CAMPAIGN_DEACTIVATED,
                targetType = "COUPON_CAMPAIGN",
                targetId = campaign.id,
                reason = reason.trim(),
                details = "redeemedCount=${campaign.redeemedCount},maxRedemptions=${campaign.maxRedemptions}",
            ),
        )
        return campaign.toView()
    }

    private fun validate(command: CreateCouponCampaignCommand, now: Instant) {
        if (command.name.isBlank() || command.eligibilityKey.isBlank()) throw CouponCampaignInvalidException("캠페인 이름과 중복 방지 키를 입력해 주세요.")
        if (!command.eligibilityKey.trim().matches(Regex("[a-zA-Z0-9-]{2,80}"))) throw CouponCampaignInvalidException("중복 방지 키는 영문, 숫자와 하이픈만 사용할 수 있습니다.")
        if (!command.redeemEndsAt.isAfter(command.redeemStartsAt) || !command.redeemEndsAt.isAfter(now)) throw CouponCampaignInvalidException("등록 종료 시각은 시작 시각과 현재보다 뒤여야 합니다.")
        if (!command.rewardItemCode.trim().matches(Regex("[a-zA-Z0-9_-]{2,80}"))) throw CouponCampaignInvalidException("꾸미기 아이템 코드는 영문, 숫자, 하이픈과 밑줄만 사용할 수 있습니다.")
        if (command.rewardQuantity !in 1..100) throw CouponCampaignInvalidException("꾸미기 아이템 지급 수량은 1~100개여야 합니다.")
        if (command.reason.trim().length !in 5..500) throw CouponCampaignInvalidException("발급 사유는 5~500자로 입력해 주세요.")
        when (command.codeType) {
            CouponCodeType.SHARED -> if (command.maxRedemptions == null || command.maxRedemptions !in 1..1_000_000) throw CouponCampaignInvalidException("공유 쿠폰의 전체 등록 한도를 확인해 주세요.")
            CouponCodeType.UNIQUE -> if (command.codeCount == null || command.codeCount !in 1..1_000) throw CouponCampaignInvalidException("개별 쿠폰 수량은 1~1000개여야 합니다.")
        }
    }

    private fun generateCode(): String = buildString(16) { repeat(16) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }
    private fun formatCode(raw: String): String = hasher.normalize(raw).chunked(4).joinToString("-")

    companion object {
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private val random = SecureRandom()
    }
}

internal fun CouponCampaignEntity.toView() = CouponCampaignView(
    id, name, eligibilityKey, codeType, status, redeemStartsAt, redeemEndsAt, maxRedemptions,
    redeemedCount, rewardType, rewardItemCode, rewardQuantity, createdAt,
)
