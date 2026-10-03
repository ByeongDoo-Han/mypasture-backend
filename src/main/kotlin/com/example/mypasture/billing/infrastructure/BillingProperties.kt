package com.example.mypasture.billing.infrastructure

import com.example.mypasture.billing.domain.BillingEnvironment
import com.example.mypasture.billing.domain.BillingProduct
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import jakarta.annotation.PostConstruct

/** 스토어 환경과 상품 allowlist를 서버에서 고정합니다. 비밀키는 앱에 전달하지 않습니다. */
@ConfigurationProperties("app.billing")
class BillingProperties {
    var enabled = false
    var environment = BillingEnvironment.SANDBOX
    var encryptionKey = ""
    var maxAttempts = 10
    var leaseSeconds = 300L
    var apple = Apple()
    var google = Google()

    /** Apple API 인증키와 루트 인증서를 별도로 관리합니다. */
    class Apple {
        var enabled = false
        var bundleId = ""
        var appAppleId: Long? = null
        var issuerId = ""
        var keyId = ""
        var privateKeyPath = ""
        var rootCertificatePaths: List<String> = emptyList()
        var products: Map<String, BillingProduct> = emptyMap()
        var monthlyProductId = ""
        var yearlyProductId = ""
        var deepProductId = ""
    }

    /** Google API는 ADC를 사용하며 Pub/Sub의 audience와 발신 서비스 계정을 고정합니다. */
    class Google {
        var enabled = false
        var packageName = ""
        var pushAudience = ""
        var pushServiceAccount = ""
        var products: Map<String, BillingProduct> = emptyMap()
        var monthlyProductId = ""
        var yearlyProductId = ""
        var deepProductId = ""
    }

    @PostConstruct
    fun validate() {
        fun catalog(existing: Map<String, BillingProduct>, monthly: String, yearly: String, deep: String): Map<String, BillingProduct> {
            val entries = listOf(monthly to BillingProduct.MONTHLY, yearly to BillingProduct.YEARLY, deep to BillingProduct.DEEP_THREE_SESSIONS).filter { it.first.isNotBlank() }
            require(entries.map { it.first }.distinct().size == entries.size)
            require(entries.all { it.first.length <= 200 && (existing[it.first] == null || existing[it.first] == it.second) })
            return existing + entries
        }
        apple.products = catalog(apple.products, apple.monthlyProductId, apple.yearlyProductId, apple.deepProductId)
        google.products = catalog(google.products, google.monthlyProductId, google.yearlyProductId, google.deepProductId)
        if (!enabled) return
        require(java.util.Base64.getDecoder().decode(encryptionKey).size == 32) { "Billing requires a base64 32-byte encryption key" }
        require(maxAttempts in 1..20 && leaseSeconds in 300..900)
        require(apple.enabled || google.enabled) { "Billing requires at least one store" }
        if (apple.enabled) {
            require(apple.bundleId.isNotBlank() && apple.issuerId.isNotBlank() && apple.keyId.isNotBlank())
            require(apple.privateKeyPath.isNotBlank() && apple.rootCertificatePaths.isNotEmpty() && apple.products.isNotEmpty())
            require(environment != BillingEnvironment.PRODUCTION || apple.appAppleId != null)
        }
        if (google.enabled) {
            require(google.packageName.isNotBlank() && google.products.isNotEmpty())
            require(google.pushAudience.startsWith("https://") && google.pushServiceAccount.endsWith(".gserviceaccount.com"))
        }
    }
}

/** 결제 설정을 Spring에 등록하되 실제 과금 연동은 enabled일 때만 초기화합니다. */
@Configuration
@EnableConfigurationProperties(BillingProperties::class)
class BillingConfiguration
