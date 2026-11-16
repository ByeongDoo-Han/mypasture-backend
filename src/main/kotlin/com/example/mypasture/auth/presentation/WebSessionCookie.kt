package com.example.mypasture.auth.presentation

import org.springframework.http.ResponseCookie
import java.time.Duration

/** 웹 refresh token의 host-only 보안 쿠키 발급과 제거 정책을 한곳에서 유지합니다. */
object WebSessionCookie {
    const val NAME = "__Host-mypasture-web-refresh"

    fun issued(token: String, ttlSeconds: Long): String = cookie(token, Duration.ofSeconds(ttlSeconds))

    fun cleared(): String = cookie("", Duration.ZERO)

    private fun cookie(value: String, age: Duration): String = ResponseCookie.from(NAME, value)
        .httpOnly(true)
        .secure(true)
        .sameSite("Strict")
        .path("/")
        .maxAge(age)
        .build()
        .toString()
}
