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
    fun `development mobile origin can call login api`() {
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
}
