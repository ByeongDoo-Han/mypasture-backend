package com.example.mypasture

import com.example.mypasture.config.AiProperties
import com.example.mypasture.config.AuthSecurityProperties
import com.example.mypasture.config.BibleProperties
import com.example.mypasture.config.CorsProperties
import com.example.mypasture.config.FirebaseProperties
import com.example.mypasture.config.JwtProperties
import com.example.mypasture.config.OAuthProperties
import com.example.mypasture.config.QuietTimeQrProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

/** Spring Boot 애플리케이션의 시작점이며 전체 `com.example.mypasture` 하위 패키지를 스캔합니다. */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(
    AiProperties::class,
    AuthSecurityProperties::class,
    BibleProperties::class,
    CorsProperties::class,
    FirebaseProperties::class,
    JwtProperties::class,
    OAuthProperties::class,
    QuietTimeQrProperties::class,
)
class MypastureApplication

fun main(args: Array<String>) {
    runApplication<MypastureApplication>(*args)
}
