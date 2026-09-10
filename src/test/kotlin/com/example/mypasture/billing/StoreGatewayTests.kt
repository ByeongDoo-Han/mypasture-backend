package com.example.mypasture.billing

import com.example.mypasture.billing.application.*
import com.example.mypasture.billing.domain.*
import com.example.mypasture.billing.infrastructure.*
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.util.UUID

/** 신뢰된 스토어 응답 파싱과 권한 판정을 외부 결제 없이 검증합니다. */
class StoreGatewayTests {
    private val mapper = jacksonObjectMapper()
    private val owner = UUID.randomUUID()
    private val properties = BillingProperties().apply {
        enabled = true
        apple.enabled = true
        apple.bundleId = "com.mypasture.app"
        apple.products = mapOf("monthly" to BillingProduct.MONTHLY, "credits" to BillingProduct.DEEP_THREE_SESSIONS)
        google.enabled = true
        google.products = apple.products
    }

    @Test
    fun `apple checks original proof product owner app environment and rejects shared purchase`() {
        val client = mock(AppleStoreClient::class.java)
        val gateway = AppleStoreGateway(client, properties)
        val valid = appleTx("credits", "Consumable")
        `when`(client.transaction("123")).thenReturn(valid)
        assertEquals(3, gateway.verify("credits", "123").units)
        for ((field, value) in listOf("bundleId" to "another.app", "environment" to "Production", "inAppOwnershipType" to "FAMILY_SHARED", "appAccountToken" to "")) {
            `when`(client.transaction("123")).thenReturn(valid.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().put(field, value))
            assertThrows(StorePurchaseRejected::class.java) { gateway.verify("credits", "123") }
        }
        `when`(client.transaction("123")).thenReturn(valid)
        assertThrows(StorePurchaseRejected::class.java) { gateway.verify("monthly", "123") }
        assertThrows(StorePurchaseRejected::class.java) { gateway.verify("credits", "https://attacker.invalid") }
    }

    @Test
    fun `apple current subscription status overrides old valid signed transaction`() {
        val client = mock(AppleStoreClient::class.java)
        val gateway = AppleStoreGateway(client, properties)
        val tx = appleTx("monthly", "Auto-Renewable Subscription")
        `when`(client.transaction("123")).thenReturn(tx)
        for (status in 1..5) {
            `when`(client.subscriptions("123")).thenReturn(listOf(AppleSubscriptionState(status, tx, mapper.readTree("""{"autoRenewStatus":0,"gracePeriodExpiresDate":1893456000000}"""))))
            val result = gateway.verify("monthly", "123")
            assertEquals(status !in listOf(1, 4), result.revoked)
            assertFalse(result.autoRenew)
        }
    }

    @Test
    fun `google pending wrong environment and unknown product cannot grant rights`() {
        val client = mock(GooglePlayClient::class.java)
        val gateway = GoogleStoreGateway(client, properties)
        val path = listOf("purchases", "subscriptionsv2", "tokens", "proof")
        `when`(client.request("GET", path)).thenReturn(googleSubscription("SUBSCRIPTION_STATE_PENDING"))
        assertThrows(StoreTemporarilyUnavailable::class.java) { gateway.verify("monthly", "proof") }
        `when`(client.request("GET", path)).thenReturn(googleSubscription("SUBSCRIPTION_STATE_ACTIVE"))
        assertFalse(gateway.verify("monthly", "proof").revoked)
        properties.environment = BillingEnvironment.PRODUCTION
        assertThrows(StorePurchaseRejected::class.java) { gateway.verify("monthly", "proof") }
        assertThrows(StorePurchaseRejected::class.java) { gateway.verify("unknown", "proof") }
    }

    @Test
    fun `google cancellation preserves term while hold and pause deny access`() {
        val client = mock(GooglePlayClient::class.java)
        val gateway = GoogleStoreGateway(client, properties)
        for (state in listOf("ACTIVE", "CANCELED", "IN_GRACE_PERIOD", "ON_HOLD", "PAUSED", "EXPIRED")) {
            `when`(client.request("GET", listOf("purchases", "subscriptionsv2", "tokens", "proof"))).thenReturn(googleSubscription("SUBSCRIPTION_STATE_$state"))
            val result = gateway.verify("monthly", "proof")
            assertEquals(state in listOf("ON_HOLD", "PAUSED", "EXPIRED"), result.revoked)
            assertEquals("order..1", result.transactionKey)
            assertEquals(billingSourceKey("google:older"), result.replacesSourceKey)
        }
    }

    @Test
    fun `consumption is not counseling usage and acknowledgement is safely retried`() {
        val client = mock(GooglePlayClient::class.java)
        val gateway = GoogleStoreGateway(client, properties)
        val path = listOf("purchases", "productsv2", "tokens", "proof")
        val response = mapper.readTree("""{
          "testPurchaseContext":{"fopType":"TEST"},"obfuscatedExternalAccountId":"$owner","orderId":"order",
          "purchaseStateContext":{"purchaseState":"PURCHASED"},
          "productLineItem":[{"productId":"credits","productOfferDetails":{"quantity":1,"refundableQuantity":1,"consumptionState":"CONSUMPTION_STATE_CONSUMED"}}]
        }""")
        `when`(client.request("GET", path)).thenReturn(response)
        assertEquals(3, gateway.verify("credits", "proof").units)
        assertFalse(gateway.verify("credits", "proof").needsAcknowledgement)
        gateway.acknowledge("credits", "proof")
        verify(client, never()).request("POST", listOf("purchases", "products", "credits", "tokens", "proof:consume"))
        (response.path("productLineItem")[0].path("productOfferDetails") as com.fasterxml.jackson.databind.node.ObjectNode).put("refundableQuantity", 0)
        assertEquals(0, gateway.verify("credits", "proof").units)
        assertTrue(gateway.verify("credits", "proof").revoked)
    }

    @Test
    fun `billing ciphertext cannot be moved to another job or modified`() {
        properties.encryptionKey = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 17 })
        val cipher = BillingProofCipher(properties)
        val id = UUID.randomUUID()
        val encoded = cipher.encrypt("sensitive-token", id)
        assertEquals("sensitive-token", cipher.decrypt(encoded, id))
        assertFalse(encoded.contains("sensitive-token"))
        assertThrows(Exception::class.java) { cipher.decrypt(encoded, UUID.randomUUID()) }
        val bytes = java.util.Base64.getDecoder().decode(encoded)
        bytes[15] = (bytes[15].toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { cipher.decrypt(java.util.Base64.getEncoder().encodeToString(bytes), id) }
    }

    @Test
    fun `temporary Apple certificate verification failure is retryable not a forged purchase`() {
        assertInstanceOf(StoreTemporarilyUnavailable::class.java, appleVerificationFailure(com.apple.itunes.storekit.verification.VerificationException(
            com.apple.itunes.storekit.verification.VerificationStatus.RETRYABLE_VERIFICATION_FAILURE)))
        assertInstanceOf(StorePurchaseRejected::class.java, appleVerificationFailure(com.apple.itunes.storekit.verification.VerificationException(
            com.apple.itunes.storekit.verification.VerificationStatus.INVALID_CERTIFICATE)))
    }

    @Test
    fun `official apple verifier rejects unsigned fabricated transaction`() {
        val trust = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
        trust.init(null as java.security.KeyStore?)
        val root = (trust.trustManagers.first { it is javax.net.ssl.X509TrustManager } as javax.net.ssl.X509TrustManager).acceptedIssuers.first()
        val verifier = com.apple.itunes.storekit.verification.SignedDataVerifier(setOf(root.encoded.inputStream()), "com.mypasture.app", null,
            com.apple.itunes.storekit.model.Environment.SANDBOX, false)
        assertThrows(com.apple.itunes.storekit.verification.VerificationException::class.java) {
            verifier.verifyAndDecodeTransaction("eyJhbGciOiJub25lIn0.eyJwcm9kdWN0SWQiOiJtb250aGx5In0.")
        }
    }

    private fun appleTx(product: String, type: String): JsonNode = mapper.readTree("""{
      "productId":"$product","type":"$type","bundleId":"com.mypasture.app","environment":"Sandbox",
      "inAppOwnershipType":"PURCHASED","appAccountToken":"$owner","transactionId":"123","originalTransactionId":"111",
      "quantity":1,"purchaseDate":1704067200000,"expiresDate":1893456000000
    }""")
    private fun googleSubscription(state: String): JsonNode = mapper.readTree("""{
      "testPurchase":{},"subscriptionState":"$state","startTime":"2026-01-01T00:00:00Z",
      "externalAccountIdentifiers":{"obfuscatedExternalAccountId":"$owner"},"linkedPurchaseToken":"older",
      "acknowledgementState":"ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
      "lineItems":[{"productId":"monthly","latestSuccessfulOrderId":"order..1","expiryTime":"2030-01-01T00:00:00Z","autoRenewingPlan":{"autoRenewEnabled":false}}]
    }""")
}
