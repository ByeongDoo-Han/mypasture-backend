package com.example.mypasture.auth.application

/** 브라우저 인가 코드를 서버에서 카카오 액세스 토큰으로 교환하는 포트입니다. */
interface KakaoWebCodeExchange {
    fun exchange(code: String, codeVerifier: String): String
}
