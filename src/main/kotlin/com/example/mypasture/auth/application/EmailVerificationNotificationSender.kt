package com.example.mypasture.auth.application

import java.time.Instant

/** 회원가입 이메일 인증번호 전달 수단을 application 계층에서 분리하는 출력 포트입니다. */
interface EmailVerificationNotificationSender {
    fun send(email: String, code: String, expiresAt: Instant)
}

/** 커밋된 인증번호 발급 결과를 비동기 메일 발송으로 전달하는 이벤트입니다. */
data class EmailVerificationRequestedEvent(
    val email: String,
    val code: String,
    val expiresAt: Instant,
)
