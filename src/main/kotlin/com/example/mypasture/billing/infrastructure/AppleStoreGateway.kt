package com.example.mypasture.billing.infrastructure

import com.apple.itunes.storekit.client.AppStoreServerAPIClient
import com.apple.itunes.storekit.model.Environment
import com.apple.itunes.storekit.verification.SignedDataVerifier
import com.example.mypasture.billing.application.*
import com.example.mypasture.billing.domain.*
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/** Apple 공식 API와 인증서 체인 검증을 테스트 가능한 경계로 분리합니다. */
interface AppleStoreClient {
    fun transaction(id: String): JsonNode
    fun subscriptions(id: String): List<AppleSubscriptionState>
    fun notification(signedPayload: String): JsonNode
    fun signedTransaction(value: String): JsonNode
}

/** 서명 검증이 끝난 구독 거래/갱신 정보와 API의 현재 상태입니다. */
data class AppleSubscriptionState(val status: Int, val transaction: JsonNode, val renewal: JsonNode)

/** 운영 앱 ID/환경/Apple 루트 인증서에 고정한 공식 검증기입니다. 키는 지연 로딩합니다. */
@Component
class OfficialAppleStoreClient(private val properties: BillingProperties, private val mapper: ObjectMapper) : AppleStoreClient {
    private val environment get() = if (properties.environment == BillingEnvironment.PRODUCTION) Environment.PRODUCTION else Environment.SANDBOX
    private fun enabled() { if (!properties.enabled || !properties.apple.enabled) throw BillingNotEnabled() }
    private val verifier by lazy {
        enabled()
        val streams = properties.apple.rootCertificatePaths.map { Files.readAllBytes(Path.of(it)).inputStream() }
        try {
            SignedDataVerifier(streams.toSet(), properties.apple.bundleId, properties.apple.appAppleId, environment, true)
        } finally { streams.forEach { it.close() } }
    }
    private val client by lazy {
        enabled()
        AppStoreServerAPIClient(Files.readString(Path.of(properties.apple.privateKeyPath)), properties.apple.keyId,
            properties.apple.issuerId, properties.apple.bundleId, environment)
    }

    override fun transaction(id: String): JsonNode = remote { signedTransaction(client.getTransactionInfo(id).signedTransactionInfo) }
    override fun signedTransaction(value: String): JsonNode = remote { mapper.valueToTree(verifier.verifyAndDecodeTransaction(value)) }
    override fun notification(signedPayload: String): JsonNode = remote { mapper.valueToTree(verifier.verifyAndDecodeNotification(signedPayload)) }
    override fun subscriptions(id: String): List<AppleSubscriptionState> = remote {
        client.getAllSubscriptionStatuses(id, null).data.orEmpty().flatMap { it.lastTransactions.orEmpty() }.map { item ->
            AppleSubscriptionState(mapper.valueToTree<JsonNode>(item).path("status").asInt(),
                signedTransaction(item.signedTransactionInfo), mapper.valueToTree(verifier.verifyAndDecodeRenewalInfo(item.signedRenewalInfo)))
        }
    }

    private fun <T> remote(action: () -> T): T {
        enabled()
        try { return action() }
        catch (error: com.apple.itunes.storekit.verification.VerificationException) { throw appleVerificationFailure(error) }
        catch (error: com.apple.itunes.storekit.client.APIException) {
            if (error.httpStatusCode == 400 || error.httpStatusCode == 404) throw StorePurchaseRejected("APPLE_PURCHASE_NOT_FOUND")
            throw StoreTemporarilyUnavailable()
        }
        catch (error: java.io.IOException) { throw StoreTemporarilyUnavailable() }
    }
}

/** 진짜 Apple 거래 중 서버 상품/계정/최신 구독 상태가 일치하는 구매만 권한으로 변환합니다. */
@Component
class AppleStoreGateway(private val client: AppleStoreClient, private val properties: BillingProperties) : StorePurchasePort {
    override val store = BillingStore.APPLE
    override fun verify(productId: String, proof: String): VerifiedStorePurchase {
        if (!properties.enabled || !properties.apple.enabled) throw BillingNotEnabled()
        if (!proof.matches(Regex("[0-9]{1,30}"))) throw StorePurchaseRejected("INVALID_TRANSACTION_ID")
        val initial = client.transaction(proof)
        if (initial.text("productId") != productId) throw StorePurchaseRejected("PRODUCT_MISMATCH")
        val original = initial.text("originalTransactionId")
        val requestedProduct = product(initial)
        val owner = owner(initial)
        if (requestedProduct.units > 0) {
            if (initial.text("type") != "Consumable" || initial.path("quantity").asInt() != 1) throw StorePurchaseRejected("UNSUPPORTED_PRODUCT_TYPE")
            val revoked = initial.hasNonNull("revocationDate")
            return VerifiedStorePurchase(owner, initial.text("transactionId"), billingSourceKey("apple:$original"),
                product = requestedProduct, financialStatus = if (revoked) BillingFinancialStatus.REFUNDED else BillingFinancialStatus.PAID,
                revoked = revoked, startsAt = null, expiresAt = null, autoRenew = false, units = if (revoked) 0 else requestedProduct.units)
        }
        val current = client.subscriptions(proof).filter { it.transaction.text("originalTransactionId") == original }
            .singleOrNull() ?: throw StorePurchaseRejected("SUBSCRIPTION_STATE_MISSING")
        val tx = current.transaction
        if (owner(tx) != owner || tx.text("type") != "Auto-Renewable Subscription") throw StorePurchaseRejected("PURCHASE_OWNER_MISMATCH")
        val actualProduct = product(tx)
        if (actualProduct.units != 0 || current.status !in 1..5) throw StorePurchaseRejected("UNSUPPORTED_SUBSCRIPTION_STATE")
        val expiry = if (current.status == 4) current.renewal.millis("gracePeriodExpiresDate") else tx.millis("expiresDate")
        return VerifiedStorePurchase(owner, tx.text("transactionId"), billingSourceKey("apple:$original"),
            product = actualProduct, financialStatus = BillingFinancialStatus.PAID,
            revoked = tx.hasNonNull("revocationDate") || current.status !in listOf(1, 4), startsAt = tx.millis("purchaseDate"),
            expiresAt = expiry, autoRenew = current.renewal.path("autoRenewStatus").asInt() == 1, units = 0)
    }
    override fun acknowledge(productId: String, proof: String) = Unit // StoreKit finish는 앱에서 서버 완료 응답 후 수행합니다.
    private fun product(tx: JsonNode) = properties.apple.products[tx.text("productId")] ?: throw StorePurchaseRejected("PRODUCT_NOT_ALLOWED")
    private fun owner(tx: JsonNode): UUID {
        if (tx.text("bundleId") != properties.apple.bundleId || tx.text("environment").uppercase() != properties.environment.name) throw StorePurchaseRejected("APP_OR_ENVIRONMENT_MISMATCH")
        if (tx.text("inAppOwnershipType") != "PURCHASED") throw StorePurchaseRejected("SHARED_PURCHASE_NOT_SUPPORTED")
        return parseOwner(tx.text("appAccountToken"))
    }
}

internal fun JsonNode.text(field: String): String = path(field).asText().takeIf { it.isNotBlank() }
    ?: throw StorePurchaseRejected("INCOMPLETE_STORE_RESPONSE")
internal fun JsonNode.millis(field: String): Instant {
    if (!path(field).isIntegralNumber) throw StorePurchaseRejected("INCOMPLETE_STORE_RESPONSE")
    return Instant.ofEpochMilli(path(field).asLong())
}
internal fun parseOwner(value: String): UUID = try { UUID.fromString(value) } catch (_: IllegalArgumentException) { throw StorePurchaseRejected("PURCHASE_OWNER_MISSING") }

/** 인증서 상태 서버의 일시 장애를 위조 서명과 구분해야 정상 구매가 영구 거절되지 않습니다. */
internal fun appleVerificationFailure(error: com.apple.itunes.storekit.verification.VerificationException): RuntimeException =
    if (error.status == com.apple.itunes.storekit.verification.VerificationStatus.RETRYABLE_VERIFICATION_FAILURE) StoreTemporarilyUnavailable()
    else StorePurchaseRejected("INVALID_APPLE_SIGNATURE")
