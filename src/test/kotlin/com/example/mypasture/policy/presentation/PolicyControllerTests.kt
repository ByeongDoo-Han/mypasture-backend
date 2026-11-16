package com.example.mypasture.policy.presentation

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/** 정책 원문 공개 조회와 회원가입 필수 동의·사용자 상태 API를 검증합니다. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = ["app.policy.enforcement-enabled=true", "app.auth.email-verification.required=false"])
class PolicyControllerTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
) {
    @Test
    fun `현재 계정 정책은 공개적으로 버전과 함께 조회된다`() {
        mockMvc.get("/api/v1/policies/current") { param("scope", "ACCOUNT") }
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(3) }
                jsonPath("$[0].version") { value("1.0.0") }
                jsonPath("$[0].content") { isNotEmpty() }
            }
    }

    @Test
    fun `현재 필수 약관에 동의하지 않으면 회원가입을 거부한다`() {
        mockMvc.post("/api/v1/auth/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"missing-policy@example.com","password":"password123","displayName":"목원"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("POLICY_CONSENT_REQUIRED") }
        }
    }

    @Test
    fun `AI 기록 정책은 최신 민감 정보 동의를 요구하고 이전 문서는 계속 조회할 수 있다`() {
        mockMvc.get("/api/v1/policies/current") { param("scope", "AI") }
            .andExpect {
                status { isOk() }
                jsonPath("$[0].version") { value("1.1.0") }
                jsonPath("$[0].content") { value(org.hamcrest.Matchers.containsString("원본 질문 생성일로부터 365일")) }
            }
        mockMvc.get("/api/v1/policies/AI_SENSITIVE_DATA/1.0.0")
            .andExpect {
                status { isOk() }
                jsonPath("$.version") { value("1.0.0") }
            }

        val suffix = System.nanoTime()
        val email = "ai-history-policy-$suffix@example.com"
        mockMvc.post("/api/v1/auth/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"password123","displayName":"정책목원하나","policyConsents":[
                {"type":"TERMS_OF_SERVICE","version":"1.0.0","agreed":true},
                {"type":"PRIVACY_COLLECTION","version":"1.0.0","agreed":true}
            ]}"""
        }.andExpect { status { isCreated() } }
        val login = mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"password123"}"""
        }.andReturn().response
        val token = objectMapper.readTree(login.contentAsString).path("token").asText()

        mockMvc.post("/api/v1/policies/consents") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"consents":[{"type":"AI_SENSITIVE_DATA","version":"1.0.0","agreed":true}]}"""
        }.andExpect { status { isOk() } }
        mockMvc.get("/api/v1/policies/consent-status") {
            header("Authorization", "Bearer $token")
            param("scopes", "AI")
        }.andExpect {
            status { isOk() }
            jsonPath("$.allRequiredAgreed") { value(false) }
        }
        mockMvc.post("/api/v1/policies/consents") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"consents":[{"type":"AI_SENSITIVE_DATA","version":"1.1.0","agreed":true}]}"""
        }.andExpect { status { isOk() } }
        mockMvc.get("/api/v1/policies/consent-status") {
            header("Authorization", "Bearer $token")
            param("scopes", "AI")
        }.andExpect {
            status { isOk() }
            jsonPath("$.allRequiredAgreed") { value(true) }
        }
    }

    @Test
    fun `회원가입은 동의를 저장하고 인증된 동의 상태는 완료로 표시된다`() {
        val suffix = System.nanoTime()
        val email = "policy-$suffix@example.com"
        val signupBody = """
            {
              "email":"$email",
              "password":"password123",
              "displayName":"정책목원",
              "policyConsents":[
                {"type":"TERMS_OF_SERVICE","version":"1.0.0","agreed":true},
                {"type":"PRIVACY_COLLECTION","version":"1.0.0","agreed":true},
                {"type":"MARKETING_NOTIFICATIONS","version":"1.0.0","agreed":false}
              ]
            }
        """.trimIndent()
        mockMvc.post("/api/v1/auth/signup") { contentType = MediaType.APPLICATION_JSON; content = signupBody }
            .andExpect { status { isCreated() } }

        val login = mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"password123"}"""
        }.andReturn().response
        val token = objectMapper.readTree(login.contentAsString).path("token").asText()

        mockMvc.get("/api/v1/policies/consent-status") {
            header("Authorization", "Bearer $token")
            param("scopes", "ACCOUNT")
        }.andExpect {
            status { isOk() }
            jsonPath("$.allRequiredAgreed") { value(true) }
            jsonPath("$.items[0].agreed") { value(true) }
        }
    }

    @Test
    fun `별도 민감 정보 동의 전에는 계정 사용자의 AI 대화를 거부한다`() {
        val suffix = System.nanoTime()
        val email = "ai-policy-$suffix@example.com"
        mockMvc.post("/api/v1/auth/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {"email":"$email","password":"password123","displayName":"정책목원둘","policyConsents":[
                  {"type":"TERMS_OF_SERVICE","version":"1.0.0","agreed":true},
                  {"type":"PRIVACY_COLLECTION","version":"1.0.0","agreed":true}
                ]}
            """.trimIndent()
        }.andExpect { status { isCreated() } }
        val login = mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"password123"}"""
        }.andReturn().response
        val token = objectMapper.readTree(login.contentAsString).path("token").asText()

        val initial = mockMvc.post("/api/v1/ai/chat") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"question":"불안할 때 읽을 말씀을 알려주세요"}"""
        }.andExpect { request { asyncStarted() } }.andReturn()
        mockMvc.perform(asyncDispatch(initial))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("POLICY_CONSENT_REQUIRED"))
    }
}
