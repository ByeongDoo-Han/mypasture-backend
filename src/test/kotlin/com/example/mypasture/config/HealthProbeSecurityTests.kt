package com.example.mypasture.config

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/** Cloud Run이 인증 없이 애플리케이션의 생존 및 준비 상태를 검사할 수 있는지 검증합니다. */
@SpringBootTest
@AutoConfigureMockMvc
class HealthProbeSecurityTests(
    @Autowired private val mockMvc: MockMvc,
) {
    @Test
    fun `생존 상태 확인은 인증 없이 접근할 수 있다`() {
        mockMvc.get("/actuator/health/liveness")
            .andExpect {
                status { isOk() }
            }
    }

    @Test
    fun `준비 상태 확인은 인증 없이 접근할 수 있다`() {
        mockMvc.get("/actuator/health/readiness")
            .andExpect {
                status { isOk() }
            }
    }
}
