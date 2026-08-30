package com.example.mypasture.coupon

import com.example.mypasture.ai.application.AiAccessPolicyResolver
import com.example.mypasture.auth.infrastructure.UserRepository
import com.example.mypasture.coupon.application.CouponAlreadyRedeemedException
import com.example.mypasture.coupon.application.CouponCampaignAdminService
import com.example.mypasture.coupon.application.CouponExhaustedException
import com.example.mypasture.coupon.application.CouponRedemptionService
import com.example.mypasture.coupon.application.CreateCouponCampaignCommand
import com.example.mypasture.coupon.application.UserEntitlementService
import com.example.mypasture.coupon.domain.CouponCodeType
import com.example.mypasture.user.domain.SubscriptionTier
import com.example.mypasture.user.domain.UserEntity
import com.example.mypasture.user.domain.UserRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** 쿠폰 등록의 중복·동시성 정책과 AI 체험 권한 연결을 실제 Spring 트랜잭션으로 검증합니다. */
@SpringBootTest
class CouponFlowTests(
    @Autowired private val users: UserRepository,
    @Autowired private val campaignService: CouponCampaignAdminService,
    @Autowired private val redemptionService: CouponRedemptionService,
    @Autowired private val entitlementService: UserEntitlementService,
    @Autowired private val aiAccessPolicyResolver: AiAccessPolicyResolver,
) {
    @Test
    fun `shared coupon grants expiring premium AI trial only once per eligibility key`() {
        val admin = admin()
        val user = user()
        val code = "TRIAL-${UUID.randomUUID().toString().take(8)}"
        campaignService.create(admin.id, command(CouponCodeType.SHARED, code, maxRedemptions = 10))

        val redeemed = redemptionService.redeem(user.id, code)

        assertEquals("7일 프리미엄 AI 체험", redeemed.campaignName)
        assertEquals(30, redeemed.entitlement.aiDailyLimit)
        assertNotNull(entitlementService.activePremiumAiTrial(user.id))
        val policy = aiAccessPolicyResolver.resolve(user.id, SubscriptionTier.FREE)
        assertEquals(SubscriptionTier.PREMIUM, policy.effectiveTier)
        assertEquals(30L, policy.reservationDailyLimit)
        assertEquals(false, policy.unlimited)
        assertNotNull(policy.entitlementExpiresAt)

        assertThrows(CouponAlreadyRedeemedException::class.java) {
            redemptionService.redeem(user.id, code)
        }
    }

    @Test
    fun `concurrent redemption of last shared slot grants exactly one entitlement`() {
        val admin = admin()
        val first = user()
        val second = user()
        val code = "LAST-${UUID.randomUUID().toString().take(8)}"
        campaignService.create(admin.id, command(CouponCodeType.SHARED, code, maxRedemptions = 1))
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = listOf(first, second).map { target ->
                executor.submit<Result<*>> {
                    ready.countDown()
                    start.await()
                    runCatching { redemptionService.redeem(target.id, code) }
                }
            }
            ready.await()
            start.countDown()
            val completed = results.map { it.get() }

            assertEquals(1, completed.count { it.isSuccess })
            assertEquals(1, completed.count { it.exceptionOrNull() is CouponExhaustedException })
        } finally {
            executor.shutdownNow()
        }
    }

    private fun command(type: CouponCodeType, sharedCode: String, maxRedemptions: Long) =
        CreateCouponCampaignCommand(
            operationId = UUID.randomUUID(),
            name = "7일 프리미엄 AI 체험",
            eligibilityKey = "premium-ai-trial-lifetime-${UUID.randomUUID()}",
            codeType = type,
            redeemStartsAt = Instant.now().minus(1, ChronoUnit.HOURS),
            redeemEndsAt = Instant.now().plus(7, ChronoUnit.DAYS),
            maxRedemptions = maxRedemptions,
            codeCount = null,
            sharedCode = sharedCode,
            entitlementDurationDays = 7,
            aiDailyLimit = 30,
            reason = "통합 테스트용 체험 쿠폰 발급",
        )

    private fun admin() = users.save(
        UserEntity(email = "coupon-admin-${UUID.randomUUID()}@example.com", displayName = "Coupon Admin", role = UserRole.ADMIN),
    )

    private fun user() = users.save(
        UserEntity(email = "coupon-user-${UUID.randomUUID()}@example.com", displayName = "Coupon User"),
    )
}
