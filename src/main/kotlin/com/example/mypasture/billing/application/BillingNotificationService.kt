package com.example.mypasture.billing.application

import com.example.mypasture.billing.domain.BillingStore
import com.example.mypasture.billing.infrastructure.*
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.util.Base64
import java.util.UUID

/** Pub/Sub의 서비스 계정, 수신 URL audience, 발급자와 만료를 검증합니다. */
interface GooglePushIdentityVerifier { fun verify(authorization: String) }

/** Google의 고정 공개키 집합을 캐싱하는 JWT 검증기로 앱 로그인 JWT와 완전히 분리합니다. */
@Component
class GoogleOidcPushVerifier(private val properties: BillingProperties) : GooglePushIdentityVerifier {
    private val decoder by lazy {
        NimbusJwtDecoder.withJwkSetUri("https://www.googleapis.com/oauth2/v3/certs").build().apply {
            val audienceAndAccount = OAuth2TokenValidator<Jwt> { jwt ->
                if (properties.google.pushAudience in jwt.audience && jwt.getClaimAsString("email") == properties.google.pushServiceAccount && jwt.claims["email_verified"] == true) {
                    OAuth2TokenValidatorResult.success()
                } else OAuth2TokenValidatorResult.failure(OAuth2Error("invalid_token"))
            }
            setJwtValidator(DelegatingOAuth2TokenValidator(JwtValidators.createDefaultWithIssuer("https://accounts.google.com"), audienceAndAccount))
        }
    }
    override fun verify(authorization: String) {
        if (!properties.enabled || !properties.google.enabled) throw BillingNotEnabled()
        if (!authorization.startsWith("Bearer ") || authorization.length > 16384) throw StorePurchaseRejected("UNAUTHENTICATED_WEBHOOK")
        try { decoder.decode(authorization.removePrefix("Bearer ")) }
        catch (_: org.springframework.security.oauth2.jwt.JwtException) { throw StorePurchaseRejected("UNAUTHENTICATED_WEBHOOK") }
    }
}

/** 인증된 알림은 직접 권한을 변경하지 않고, DB에 재검증 작업을 커밋한 뒤 수신 성공을 반환합니다. */
@Service
class BillingNotificationService(
    private val apple: AppleStoreClient,
    private val google: GoogleStoreGateway,
    private val googleIdentity: GooglePushIdentityVerifier,
    private val verification: BillingVerificationService,
    private val properties: BillingProperties,
    private val mapper: ObjectMapper,
) {
    fun apple(signedPayload: String): BillingVerificationView? {
        val notification = apple.notification(signedPayload)
        if (notification.path("notificationType").asText() == "TEST") return null
        val tx = apple.signedTransaction(notification.path("data").text("signedTransactionInfo"))
        val owner = verification.notificationOwner(parseOwner(tx.text("appAccountToken")))
        return verification.enqueue(owner, eventId("apple", notification.text("notificationUUID")), BillingStore.APPLE,
            tx.text("productId"), tx.text("transactionId"), notification = true)
    }

    fun google(authorization: String, messageId: String, data: String): BillingVerificationView? {
        googleIdentity.verify(authorization)
        val payload = try { mapper.readTree(Base64.getDecoder().decode(data)) }
        catch (_: Exception) { throw StorePurchaseRejected("INVALID_NOTIFICATION") }
        if (payload.text("packageName") != properties.google.packageName) throw StorePurchaseRejected("APP_OR_ENVIRONMENT_MISMATCH")
        if (payload.hasNonNull("testNotification")) return null
        val subscription = payload.path("subscriptionNotification")
        val product = payload.path("oneTimeProductNotification")
        val voided = payload.path("voidedPurchaseNotification")
        val section = listOf(subscription, product, voided).filter { !it.isMissingNode && !it.isNull }.singleOrNull()
            ?: throw StorePurchaseRejected("UNSUPPORTED_NOTIFICATION")
        val proof = section.text("purchaseToken")
        val consumable = !product.isMissingNode || voided.path("productType").asInt() == 2
        val hinted = section.path(if (consumable) "sku" else "subscriptionId").asText()
        val candidates = if (hinted.isNotBlank()) listOf(hinted) else properties.google.products.filterValues { (it.units > 0) == consumable }.keys.toList()
        var rejected: StorePurchaseRejected? = null
        for (candidate in candidates) {
            try {
                // Google 알림에는 우리 계정 ID가 없습니다. 최초 조회로 소유자를 찾고 worker에서 다시 검증합니다.
                val verified = google.verify(candidate, proof)
                val userId = verification.notificationOwner(verified.ownerToken)
                return verification.enqueue(userId, eventId("google", messageId), BillingStore.GOOGLE, candidate, proof, notification = true)
            } catch (error: StorePurchaseRejected) { rejected = error }
        }
        throw rejected ?: StorePurchaseRejected("PRODUCT_NOT_ALLOWED")
    }
    private fun eventId(store: String, id: String) = UUID.nameUUIDFromBytes("$store:$id".toByteArray())
}
