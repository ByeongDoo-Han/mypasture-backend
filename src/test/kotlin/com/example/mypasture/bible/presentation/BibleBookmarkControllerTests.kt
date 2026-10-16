package com.example.mypasture.bible.presentation

import com.example.mypasture.auth.domain.uniqueTestNickname

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 말씀 저장 API의 멱등성, 사용자 격리, 동시 요청과 인증 정책을 검증합니다. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "app.bible.kor1910-html-path=src/test/resources/bible/kor_html",
        "app.bible.import-enabled=true",
        "app.firebase.enabled=false",
        "app.auth.email-verification.required=false",
    ],
)
class BibleBookmarkControllerTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
) {
    @Test
    fun `authenticated user saves lists and deletes a verse bookmark idempotently`() {
        val token = createUserToken()
        val path = "/api/v1/bookmarks/KOR1910/GEN/1/1"

        val firstId = save(path, token)
        assertEquals(firstId, save(path, token))

        mockMvc.get("/api/v1/bookmarks/status") {
            header("Authorization", "Bearer $token")
            param("version", "KOR1910")
            param("bookCode", "GEN")
            param("chapter", "1")
        }.andExpect {
            status { isOk() }
            jsonPath("$.verseNumbers.length()") { value(1) }
            jsonPath("$.verseNumbers[0]") { value(1) }
        }

        mockMvc.get("/api/v1/bookmarks") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.totalElements") { value(1) }
            jsonPath("$.items.length()") { value(1) }
            jsonPath("$.items[0].id") { value(firstId) }
            jsonPath("$.items[0].bookName") { value("창세기") }
            jsonPath("$.items[0].text") { value("태초에 하나님이 천지를 창조하시니라") }
        }

        mockMvc.delete(path) {
            header("Authorization", "Bearer $token")
        }.andExpect { status { isNoContent() } }

        mockMvc.get("/api/v1/bookmarks") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.totalElements") { value(0) }
        }
    }

    @Test
    fun `bookmarks are isolated by authenticated user`() {
        val ownerToken = createUserToken()
        val otherToken = createUserToken()
        save("/api/v1/bookmarks/KOR1910/GEN/1/1", ownerToken)

        mockMvc.get("/api/v1/bookmarks") {
            header("Authorization", "Bearer $otherToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$.totalElements") { value(0) }
        }

        mockMvc.delete("/api/v1/bookmarks/KOR1910/GEN/1/1") {
            header("Authorization", "Bearer $otherToken")
        }.andExpect { status { isNoContent() } }

        mockMvc.get("/api/v1/bookmarks") {
            header("Authorization", "Bearer $ownerToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$.totalElements") { value(1) }
        }
    }

    @Test
    fun `concurrent saves for the same user and verse converge to one bookmark`() {
        val token = createUserToken()
        val path = "/api/v1/bookmarks/KOR1910/GEN/1/1"
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val requests = List(2) {
                executor.submit<String> {
                    start.await(5, TimeUnit.SECONDS)
                    save(path, token)
                }
            }
            start.countDown()
            val ids = requests.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, ids.toSet().size)

            mockMvc.get("/api/v1/bookmarks") {
                header("Authorization", "Bearer $token")
            }.andExpect {
                status { isOk() }
                jsonPath("$.totalElements") { value(1) }
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `bookmark endpoints reject unauthenticated and invalid requests`() {
        mockMvc.get("/api/v1/bookmarks")
            .andExpect { status { isUnauthorized() } }

        val token = createUserToken()
        mockMvc.get("/api/v1/bookmarks?page=-1") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_BIBLE_REQUEST") }
        }

        mockMvc.put("/api/v1/bookmarks/KOR1910/GEN/999/1") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("BIBLE_NOT_FOUND") }
        }
    }

    private fun save(path: String, token: String): String {
        val result = mockMvc.put(path) {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isOk() }
            jsonPath("$.chapter") { value(1) }
            jsonPath("$.verse") { value(1) }
        }.andReturn()
        return objectMapper.readTree(result.response.contentAsString)["id"].asText()
    }

    private fun createUserToken(): String {
        val email = "bookmark-${UUID.randomUUID()}@example.com"
        val password = "password123!"
        mockMvc.post("/api/v1/auth/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"$password","displayName":"${uniqueTestNickname()}"}"""
        }.andExpect { status { isCreated() } }

        val result = mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"$password"}"""
        }.andExpect { status { isOk() } }.andReturn()
        return objectMapper.readTree(result.response.contentAsString)["token"].asText()
    }
}
