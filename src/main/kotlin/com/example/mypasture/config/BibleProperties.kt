package com.example.mypasture.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** `app.bible` 설정값을 타입 안전하게 바인딩하는 configuration properties 클래스입니다. */
@ConfigurationProperties("app.bible")
data class BibleProperties(
    val kor1910HtmlPath: String,
    val importEnabled: Boolean = false,
)
