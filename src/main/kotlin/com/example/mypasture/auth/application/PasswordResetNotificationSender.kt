package com.example.mypasture.auth.application

import java.time.Instant

/** 비밀번호 재설정 링크 전달 수단을 application 계층에서 분리하는 출력 포트입니다. */
interface PasswordResetNotificationSender {
    fun send(
        email: String,
        displayName: String,
        rawToken: String,
        expiresAt: Instant,
    )
}

/** 트랜잭션 커밋 후 재설정 링크를 전달하기 위한 도메인 이벤트입니다. */
data class PasswordResetRequestedEvent(
    val email: String,
    val displayName: String,
    val rawToken: String,
    val expiresAt: Instant,
)
