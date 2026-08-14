package com.example.mypasture.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** Firebase Admin SDK 초기화와 푸시 발송 여부를 바인딩합니다. */
@ConfigurationProperties("app.firebase")
data class FirebaseProperties(
    val enabled: Boolean = false,
    val projectId: String = "",
    val credentialsPath: String = "",
)
