package com.example.mypasture.billing.infrastructure

import com.example.mypasture.billing.application.*
import com.example.mypasture.billing.domain.*
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.auth.oauth2.GoogleCredentials
import org.springframework.stereotype.Component
import org.springframework.web.util.UriComponentsBuilder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/** Google Developer API의 고정 호스트 통신을 파싱/정책 코드와 분리합니다. */
interface GooglePlayClient { fun request(method: String, segments: List<String>): JsonNode }

/** Cloud Run 서비스 계정의 ADC로만 인증하며 구매 토큰을 URL 로그에 남기지 않습니다. */
@Component
class OfficialGooglePlayClient(private val properties: BillingProperties, private val mapper: ObjectMapper) : GooglePlayClient {
    private val credentials by lazy { GoogleCredentials.getApplicationDefault().createScoped("https://www.googleapis.com/auth/androidpublisher") }
    private val client by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build() }
    override fun request(method: String, segments: List<String>): JsonNode {
        if (!properties.enabled || !properties.google.enabled) throw BillingNotEnabled()
        try {
            credentials.refreshIfExpired()
            val uri = UriComponentsBuilder.fromUriString("https://androidpublisher.googleapis.com/androidpublisher/v3/applications")
                .pathSegment(properties.google.packageName, *segments.toTypedArray()).build().encode().toUri()
            val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer ${credentials.accessToken.tokenValue}")
                .header("Content-Type", "application/json")
                .method(method, if (method == "GET") HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString("{}"))
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() in listOf(400, 404, 410)) throw StorePurchaseRejected("GOOGLE_PURCHASE_NOT_FOUND")
            if (response.statusCode() !in 200..299) throw StoreTemporarilyUnavailable()
            return if (response.body().isBlank()) mapper.createObjectNode() else mapper.readTree(response.body())
        } catch (_: java.io.IOException) { throw StoreTemporarilyUnavailable() }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); throw StoreTemporarilyUnavailable() }
    }
}

/** Play API의 실제 상품과 계정/상태를 검증하고 승인과 소비는 권한 저장 이후 별도로 실행합니다. */
@Component
class GoogleStoreGateway(private val client: GooglePlayClient, private val properties: BillingProperties) : StorePurchasePort {
    override val store = BillingStore.GOOGLE
    override fun verify(productId: String, proof: String): VerifiedStorePurchase {
        if (!properties.enabled || !properties.google.enabled) throw BillingNotEnabled()
        val expected = properties.google.products[productId] ?: throw StorePurchaseRejected("PRODUCT_NOT_ALLOWED")
        val response = read(expected, proof)
        val test = if (expected.units == 0) response.hasNonNull("testPurchase") else response.hasNonNull("testPurchaseContext")
        if (test != (properties.environment == BillingEnvironment.SANDBOX)) throw StorePurchaseRejected("STORE_ENVIRONMENT_MISMATCH")
        val source = billingSourceKey("google:$proof")
        if (expected.units > 0) {
            val state = response.path("purchaseStateContext").text("purchaseState")
            if (state == "PENDING") throw StoreTemporarilyUnavailable()
            if (state !in listOf("PURCHASED", "CANCELLED")) throw StorePurchaseRejected("UNSUPPORTED_PURCHASE_STATE")
            val item = response.path("productLineItem").singleOrNull() ?: throw StorePurchaseRejected("MULTI_ITEM_NOT_SUPPORTED")
            if (item.text("productId") != productId) throw StorePurchaseRejected("PRODUCT_MISMATCH")
            val offer = item.path("productOfferDetails")
            if (offer.path("quantity").asInt() != 1 || !offer.path("refundableQuantity").isIntegralNumber) throw StorePurchaseRejected("UNSUPPORTED_QUANTITY")
            val remaining = offer.path("refundableQuantity").asInt()
            if (remaining !in 0..1) throw StorePurchaseRejected("UNSUPPORTED_QUANTITY")
            val revoked = state == "CANCELLED" || remaining == 0
            return VerifiedStorePurchase(parseOwner(response.text("obfuscatedExternalAccountId")), response.text("orderId"), source,
                product = expected, financialStatus = if (revoked) BillingFinancialStatus.REFUNDED else BillingFinancialStatus.PAID,
                revoked = revoked, startsAt = null, expiresAt = null, autoRenew = false, units = if (revoked) 0 else expected.units,
                needsAcknowledgement = !revoked && offer.text("consumptionState") != "CONSUMPTION_STATE_CONSUMED")
        }
        val state = response.text("subscriptionState")
        if (state == "SUBSCRIPTION_STATE_PENDING") throw StoreTemporarilyUnavailable()
        if (state !in SUBSCRIPTION_STATES) throw StorePurchaseRejected("UNSUPPORTED_SUBSCRIPTION_STATE")
        val item = response.path("lineItems").singleOrNull() ?: throw StorePurchaseRejected("MULTI_ITEM_NOT_SUPPORTED")
        val actualProduct = properties.google.products[item.text("productId")] ?: throw StorePurchaseRejected("PRODUCT_NOT_ALLOWED")
        if (actualProduct.units > 0) throw StorePurchaseRejected("PRODUCT_MISMATCH")
        val owner = parseOwner(response.path("externalAccountIdentifiers").text("obfuscatedExternalAccountId"))
        return VerifiedStorePurchase(owner, item.text("latestSuccessfulOrderId"), source,
            replacesSourceKey = response.path("linkedPurchaseToken").asText().takeIf { it.isNotBlank() }?.let { billingSourceKey("google:$it") },
            product = actualProduct, financialStatus = BillingFinancialStatus.PAID,
            revoked = state !in setOf("SUBSCRIPTION_STATE_ACTIVE", "SUBSCRIPTION_STATE_IN_GRACE_PERIOD", "SUBSCRIPTION_STATE_CANCELED"),
            startsAt = timestamp(response.text("startTime")), expiresAt = timestamp(item.text("expiryTime")),
            autoRenew = item.path("autoRenewingPlan").path("autoRenewEnabled").asBoolean(false), units = 0,
            needsAcknowledgement = response.text("acknowledgementState") == "ACKNOWLEDGEMENT_STATE_PENDING")
    }

    override fun acknowledge(productId: String, proof: String) {
        val product = properties.google.products[productId] ?: throw StorePurchaseRejected("PRODUCT_NOT_ALLOWED")
        val current = read(product, proof)
        if (product.units > 0) {
            val offer = current.path("productLineItem").single().path("productOfferDetails")
            if (offer.path("consumptionState").asText() == "CONSUMPTION_STATE_CONSUMED") return
            client.request("POST", listOf("purchases", "products", productId, "tokens", "$proof:consume"))
        } else {
            if (current.path("acknowledgementState").asText() == "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED") return
            val actualProductId = current.path("lineItems").single().text("productId")
            if (properties.google.products[actualProductId]?.units != 0) throw StorePurchaseRejected("PRODUCT_NOT_ALLOWED")
            client.request("POST", listOf("purchases", "subscriptions", actualProductId, "tokens", "$proof:acknowledge"))
        }
    }
    private fun read(product: BillingProduct, proof: String) = client.request("GET",
        listOf("purchases", if (product.units == 0) "subscriptionsv2" else "productsv2", "tokens", proof))
    private fun timestamp(value: String): Instant = try { Instant.parse(value) } catch (_: java.time.DateTimeException) { throw StorePurchaseRejected("INVALID_STORE_TIMESTAMP") }
    companion object {
        private val SUBSCRIPTION_STATES = setOf("SUBSCRIPTION_STATE_ACTIVE", "SUBSCRIPTION_STATE_IN_GRACE_PERIOD",
            "SUBSCRIPTION_STATE_CANCELED", "SUBSCRIPTION_STATE_ON_HOLD", "SUBSCRIPTION_STATE_PAUSED", "SUBSCRIPTION_STATE_EXPIRED")
    }
}
