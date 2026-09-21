package com.example.mypasture.auth.application

import java.time.Instant

/** 관리자 일회용 코드를 평문 저장이나 로그 없이 전송하는 경계입니다. */
interface AdminEmailLoginSender {
    fun send(email: String, code: String, expiresAt: Instant)
}

/** 제공자 오류에 포함될 수 있는 주소와 코드를 외부로 전파하지 않는 발송 실패입니다. */
class AdminEmailDeliveryException : RuntimeException("Admin email delivery failed")
