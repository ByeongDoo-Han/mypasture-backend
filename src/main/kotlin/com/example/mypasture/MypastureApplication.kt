package com.example.mypasture

import com.example.mypasture.config.AiProperties
import com.example.mypasture.config.AuthSecurityProperties
import com.example.mypasture.config.BibleProperties
import com.example.mypasture.config.CorsProperties
import com.example.mypasture.config.CouponProperties
import com.example.mypasture.config.DailyWordProperties
import com.example.mypasture.config.EmailVerificationProperties
import com.example.mypasture.config.FirebaseProperties
import com.example.mypasture.config.JwtProperties
import com.example.mypasture.config.NotificationDeliveryProperties
import com.example.mypasture.config.OAuthProperties
import com.example.mypasture.config.QuietTimeQrProperties
import com.example.mypasture.config.QuietTimeRealtimeProperties
import com.example.mypasture.config.RateLimitProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.scheduling.annotation.EnableScheduling
import kotlin.system.exitProcess

/** Spring Boot 애플리케이션의 시작점이며 전체 `com.example.mypasture` 하위 패키지를 스캔합니다. */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(
    AiProperties::class,
    AuthSecurityProperties::class,
    BibleProperties::class,
    CorsProperties::class,
    CouponProperties::class,
    DailyWordProperties::class,
    EmailVerificationProperties::class,
    FirebaseProperties::class,
    JwtProperties::class,
    NotificationDeliveryProperties::class,
    OAuthProperties::class,
    QuietTimeQrProperties::class,
    QuietTimeRealtimeProperties::class,
    RateLimitProperties::class,
)
class MypastureApplication

fun main(args: Array<String>) {
    val migrationOnly = System.getenv("APP_MIGRATION_ONLY").toBoolean()
    val embeddingBackfillOnly = System.getenv("APP_EMBEDDING_BACKFILL_ONLY").toBoolean()
    val batchOnly = migrationOnly || embeddingBackfillOnly
    val application = createMypastureApplication(batchOnly)
    val context = application.run(*args)
    if (batchOnly) exitProcess(SpringApplication.exit(context))
}

/** Cloud Run migration 및 embedding Job에서는 HTTP 서버 없이 작업 완료 후 종료하도록 실행 형태를 분리합니다. */
internal fun createMypastureApplication(batchOnly: Boolean) =
    SpringApplication(MypastureApplication::class.java).apply {
        if (batchOnly) webApplicationType = WebApplicationType.NONE
    }
