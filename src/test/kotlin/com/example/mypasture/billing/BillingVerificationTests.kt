package com.example.mypasture.billing

import com.example.mypasture.ai.application.AiAccessPolicyResolver
import com.example.mypasture.auth.infrastructure.JwtTokenProvider
import com.example.mypasture.auth.infrastructure.UserRepository
import com.example.mypasture.billing.application.*
import com.example.mypasture.billing.domain.*
import com.example.mypasture.billing.infrastructure.*
import com.example.mypasture.user.domain.SubscriptionTier
import com.example.mypasture.user.domain.UserEntity
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.http.MediaType
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 실제 DB 트랜잭션으로 구매 큐, 소유권, lease fencing과 AI 권한을 검증합니다. 외부 결제는 호출하지 않습니다. */
@SpringBootTest(properties = [
    "app.billing.enabled=true", "app.billing.apple.enabled=false", "app.billing.google.enabled=true",
    "app.billing.encryption-key=ERERERERERERERERERERERERERERERERERERERERERE=",
    "app.billing.google.package-name=com.mypasture.app", "app.billing.google.monthly-product-id=monthly",
    "app.billing.google.deep-product-id=credits", "app.billing.google.push-audience=https://billing.example.test/webhook",
    "app.billing.google.push-service-account=push@example.iam.gserviceaccount.com", "app.worker.enabled=false",
    "app.policy.enforcement-enabled=false",
])
@AutoConfigureMockMvc
class BillingVerificationTests(
    @Autowired private val users: UserRepository,
    @Autowired private val service: BillingVerificationService,
    @Autowired private val jobs: BillingVerificationJobRepository,
    @Autowired private val accounts: BillingAccountRepository,
    @Autowired private val purchases: BillingPurchaseRepository,
    @Autowired private val cipher: BillingProofCipher,
    @Autowired private val access: AiAccessPolicyResolver,
    @Autowired private val tokens: JwtTokenProvider,
    @Autowired private val mvc: MockMvc,
) {
    @Test
    fun `duplicate request stores one encrypted proof and rejects changed body`() {
        val owner = user()
        val request = UUID.randomUUID()
        val job = enqueue(owner.id, request)
        assertEquals(job.id, enqueue(owner.id, request).id)
        assertThrows(BillingConflictException::class.java) { service.enqueue(owner.id, request, BillingStore.GOOGLE, "monthly", "another-proof") }
        val stored = jobs.findById(job.id).orElseThrow()
        assertNotEquals("proof", stored.encryptedProof)
        assertFalse(job.toString().contains("proof"))
        assertEquals("proof", cipher.decrypt(stored.encryptedProof, stored.id))
        assertThrows(BillingRequestNotFound::class.java) { service.status(user().id, job.id) }
    }

    @Test
    fun `parallel claims select one owner and expired workers cannot overwrite newer state`() {
        val owner = user()
        val job = enqueue(owner.id)
        val executor = Executors.newFixedThreadPool(4)
        val ready = CountDownLatch(4)
        val start = CountDownLatch(1)
        val claims = try {
            val futures = (1..4).map { executor.submit<BillingVerificationWork?> {
                ready.countDown(); check(start.await(15, TimeUnit.SECONDS)); service.claim(job.id)
            } }
            check(ready.await(15, TimeUnit.SECONDS)); start.countDown()
            futures.map { it.get(30, TimeUnit.SECONDS) }.filterNotNull()
        } finally { start.countDown(); executor.shutdownNow() }
        assertEquals(1, claims.size)
        val old = claims.single()
        expireLease(owner.id)
        due(job.id)
        val current = service.claim(job.id)!!
        val valid = subscription(current)
        service.verified(current, valid.copy(revoked = true))
        service.verified(old, valid)
        assertNull(service.entitlements(owner.id).premiumExpiresAt)
        assertTrue(purchases.findByStoreAndEnvironmentAndTransactionKey(BillingStore.GOOGLE, BillingEnvironment.SANDBOX, valid.transactionKey)!!.accessRevoked)
    }

    @Test
    fun `wrong owner cannot grant and transient failures retry without rights`() {
        val owner = user()
        val job = enqueue(owner.id)
        val port = FakeStore { _, _ -> throw StoreTemporarilyUnavailable() }
        val worker = BillingVerificationWorker(service, listOf(port), cipher)
        worker.process(job.id)
        assertEquals(BillingVerificationPhase.VERIFY, service.status(owner.id, job.id).phase)
        assertEquals(1, jobs.findById(job.id).orElseThrow().attempts)
        assertNull(service.entitlements(owner.id).premiumExpiresAt)
        port.result = { _, _ -> subscription(owner.id).copy(ownerToken = UUID.randomUUID()) }
        due(job.id)
        worker.process(job.id)
        assertEquals(BillingVerificationPhase.REJECTED, service.status(owner.id, job.id).phase)
        assertNull(service.entitlements(owner.id).premiumExpiresAt)
    }

    @Test
    fun `acknowledgement failure retries without issuing a second credit lot`() {
        val owner = user()
        val job = enqueue(owner.id, product = "credits")
        val result = subscription(owner.id).copy(product = BillingProduct.DEEP_THREE_SESSIONS, startsAt = null, expiresAt = null,
            autoRenew = false, units = 3, needsAcknowledgement = true)
        val port = FakeStore { _, _ -> result }
        port.failAcknowledgement = true
        val worker = BillingVerificationWorker(service, listOf(port), cipher)
        worker.process(job.id)
        assertEquals(BillingVerificationPhase.ACKNOWLEDGE, service.status(owner.id, job.id).phase)
        assertEquals(3L, service.entitlements(owner.id).availableDeepSessions)
        worker.process(job.id)
        assertEquals(BillingVerificationPhase.ACKNOWLEDGE, service.status(owner.id, job.id).phase)
        port.failAcknowledgement = false
        due(job.id)
        worker.process(job.id)
        assertEquals(BillingVerificationPhase.COMPLETE, service.status(owner.id, job.id).phase)
        assertEquals(3L, service.entitlements(owner.id).availableDeepSessions)
        val duplicate = enqueue(owner.id, product = "credits")
        worker.process(duplicate.id)
        worker.process(duplicate.id)
        assertEquals(3L, service.entitlements(owner.id).availableDeepSessions)
        assertEquals(1, jobs.findAllByUserIdAndRequestHashAndRefreshEnabledTrue(owner.id, jobs.findById(job.id).orElseThrow().requestHash).size)
    }

    @Test
    fun `replacement source cannot be revived by an older token`() {
        val owner = user()
        val first = service.claim(enqueue(owner.id).id)!!
        val original = subscription(first)
        service.verified(first, original)
        val second = service.claim(enqueue(owner.id).id)!!
        val replacement = subscription(second).copy(replacesSourceKey = original.sourceKey)
        service.verified(second, replacement)
        val third = service.claim(enqueue(owner.id).id)!!
        assertThrows(StorePurchaseRejected::class.java) { service.verified(third, original) }
        service.failed(third, "PURCHASE_SOURCE_REPLACED", true)
        assertTrue(purchases.findByStoreAndEnvironmentAndTransactionKey(BillingStore.GOOGLE, BillingEnvironment.SANDBOX, original.transactionKey)!!.accessRevoked)
        assertNotNull(service.entitlements(owner.id).premiumExpiresAt)
    }

    @Test
    fun `refresh changes AI entitlement even if login JWT claims premium`() {
        val owner = user()
        assertEquals(SubscriptionTier.FREE, access.resolve(SubscriptionTier.PREMIUM, owner.id).effectiveTier)
        val job = enqueue(owner.id)
        val first = service.claim(job.id)!!
        val result = subscription(first)
        service.verified(first, result)
        assertEquals(SubscriptionTier.PREMIUM, access.resolve(SubscriptionTier.FREE, owner.id).effectiveTier)
        due(job.id)
        val refresh = service.claim(job.id)!!
        service.verified(refresh, result.copy(revoked = true))
        assertEquals(SubscriptionTier.FREE, access.resolve(SubscriptionTier.PREMIUM, owner.id).effectiveTier)
    }

    @Test
    fun `API requires login hides another users job and validates input`() {
        mvc.perform(get("/api/v1/billing/account")).andExpect(status().isUnauthorized)
        val owner = user()
        val authorization = "Bearer ${tokens.issueAccessToken(owner).accessToken}"
        mvc.perform(get("/api/v1/billing/account").header("Authorization", authorization)).andExpect(status().isOk)
        mvc.perform(get("/api/v1/billing/verifications/${enqueue(user().id).id}").header("Authorization", authorization)).andExpect(status().isNotFound)
        mvc.perform(post("/api/v1/billing/verifications").header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON)
            .content("""{"requestId":"${UUID.randomUUID()}","store":"GOOGLE","productId":"monthly","proof":""}"""))
            .andExpect(status().isBadRequest)
        mvc.perform(post("/api/v1/billing/verifications").header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON)
            .content("""{"requestId":"${UUID.randomUUID()}","store":"GOOGLE","productId":"monthly","proof":"test-proof"}"""))
            .andExpect(status().isAccepted)
    }

    @Test
    fun `unauthenticated Google webhook does not enqueue a purchase`() {
        val before = jobs.count()
        mvc.perform(post("/api/v1/billing/webhooks/google").header("Authorization", "invalid")
            .contentType(MediaType.APPLICATION_JSON).content("""{"message":{"messageId":"123","data":"e30="}}"""))
            .andExpect(status().isUnauthorized)
        assertEquals(before, jobs.count())
    }

    private fun user() = users.save(UserEntity(displayName = "Store verification test"))
    private fun enqueue(owner: UUID, request: UUID = UUID.randomUUID(), product: String = "monthly") =
        service.enqueue(owner, request, BillingStore.GOOGLE, product, "proof")
    private fun due(id: UUID) { val job = jobs.findById(id).orElseThrow(); job.nextAttemptAt = Instant.now().minusSeconds(1); jobs.saveAndFlush(job) }
    private fun expireLease(owner: UUID) { val account = accounts.findById(owner).orElseThrow(); account.leaseExpiresAt = Instant.now().minusSeconds(1); accounts.saveAndFlush(account) }
    private fun subscription(work: BillingVerificationWork) = subscription(work.userId)
    private fun subscription(owner: UUID) = VerifiedStorePurchase(service.accountToken(owner), UUID.randomUUID().toString(), billingSourceKey(UUID.randomUUID().toString()),
        product = BillingProduct.MONTHLY, financialStatus = BillingFinancialStatus.PAID, revoked = false,
        startsAt = Instant.now().minusSeconds(10), expiresAt = Instant.now().plusSeconds(86400), autoRenew = true, units = 0)

    /** 네트워크 장애와 스토어 상태를 명시적으로 제어하는 테스트 전용 포트입니다. */
    private class FakeStore(var result: (String, String) -> VerifiedStorePurchase) : StorePurchasePort {
        override val store = BillingStore.GOOGLE
        var failAcknowledgement = false
        override fun verify(productId: String, proof: String) = result(productId, proof)
        override fun acknowledge(productId: String, proof: String) { if (failAcknowledgement) throw StoreTemporarilyUnavailable() }
    }
}
