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
    fun `current account policies are public and versioned`() {
        mockMvc.get("/api/v1/policies/current") { param("scope", "ACCOUNT") }
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(3) }
                jsonPath("$[0].version") { value("1.0.0") }
                jsonPath("$[0].content") { isNotEmpty() }
            }
    }

    @Test
    fun `signup rejects a request without current required consent`() {
        mockMvc.post("/api/v1/auth/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"missing-policy@example.com","password":"password123","displayName":"목원"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("POLICY_CONSENT_REQUIRED") }
        }
    }

    @Test
    fun `AI history policy requires current sensitive consent while old document remains readable`() {
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
            content = """{"email":"$email","password":"password123","displayName":"AI 정책 목원","policyConsents":[
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
    fun `signup records consent and authenticated status is complete`() {
        val suffix = System.nanoTime()
        val email = "policy-$suffix@example.com"
        val signupBody = """
            {
              "email":"$email",
              "password":"password123",
              "displayName":"정책 목원",
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
    fun `AI chat rejects account user before separate sensitive data consent`() {
        val suffix = System.nanoTime()
        val email = "ai-policy-$suffix@example.com"
        mockMvc.post("/api/v1/auth/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {"email":"$email","password":"password123","displayName":"AI 정책 목원","policyConsents":[
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
