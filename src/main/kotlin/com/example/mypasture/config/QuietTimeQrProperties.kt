package com.example.mypasture.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** 큐티 모임 QR의 기본 진행 시간, 만료 유예시간, 링크 형식을 관리하는 설정입니다. */
@ConfigurationProperties("app.quiet-time.qr")
data class QuietTimeQrProperties(
    val defaultMeetingDurationMinutes: Long = 120,
    val expiryGraceMinutes: Long = 30,
    val inviteBaseUrl: String = "https://app.mypasture.app/q/qt",
)
