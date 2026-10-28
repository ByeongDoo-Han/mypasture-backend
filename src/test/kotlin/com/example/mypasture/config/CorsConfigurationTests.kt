package com.example.mypasture.config

import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.options

/** 모바일 웹의 인증 요청이 브라우저 사전 요청 단계에서 차단되지 않는지 검증합니다. */
@SpringBootTest
@AutoConfigureMockMvc
class CorsConfigurationTests(
    @Autowired private val mockMvc: MockMvc,
) {
    @Test
    fun `개발용 모바일 출처는 로그인 API를 호출할 수 있다`() {
        mockMvc.options("/api/v1/auth/login") {
            header("Origin", "http://localhost:8084")
            header("Access-Control-Request-Method", "POST")
            header("Access-Control-Request-Headers", "content-type")
        }.andExpect {
            status { isOk() }
            header { string("Access-Control-Allow-Origin", "http://localhost:8084") }
            header { string("Access-Control-Allow-Methods", containsString("POST")) }
        }
    }

    @Test
    fun `알 수 없는 브라우저 출처는 거부된다`() {
        mockMvc.options("/api/v1/auth/login") {
            header("Origin", "https://attacker.example")
            header("Access-Control-Request-Method", "POST")
            header("Access-Control-Request-Headers", "content-type")
        }.andExpect {
            status { isForbidden() }
            header { doesNotExist("Access-Control-Allow-Origin") }
        }
    }

    @Test
    fun `쿠키를 쓰는 브라우저 요청은 웹 세션 API에서만 허용된다`() {
        mockMvc.options("/api/v1/auth/web/refresh") {
            header("Origin", "http://localhost:8084")
            header("Access-Control-Request-Method", "POST")
            header("Access-Control-Request-Headers", "content-type,x-app-environment")
        }.andExpect {
            status { isOk() }
            header { string("Access-Control-Allow-Credentials", "true") }
        }
        mockMvc.options("/api/v1/auth/login") {
            header("Origin", "http://localhost:8084")
            header("Access-Control-Request-Method", "POST")
            header("Access-Control-Request-Headers", "content-type")
        }.andExpect {
            status { isOk() }
            header { doesNotExist("Access-Control-Allow-Credentials") }
        }
        mockMvc.options("/api/v1/auth/web/refresh") {
            header("Origin", "https://mypasture.app")
            header("Access-Control-Request-Method", "POST")
        }.andExpect { status { isForbidden() } }
    }
}
